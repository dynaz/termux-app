/*
 * The built-in RDP client of TermiNow: a small JNI layer over the client API of FreeRDP 3.
 *
 * One connection is one TerminowRdp: its FreeRDP context, its client thread and its queue of
 * input. The client thread connects, runs the event loop and sends the input that the UI queued,
 * so FreeRDP is only ever used from that thread. The desktop is drawn by the GDI of FreeRDP and
 * copied into the Android Bitmap of the viewer after each paint. A certificate that can not be
 * verified, or that changed, and missing credentials are asked to the app, which blocks this
 * thread until the user answered: nothing is accepted without it.
 *
 * Credentials come from the app as strings and only go into the settings of the connection;
 * they are never logged. The clipboard is text only, and only when the host has it turned on.
 */

#include <freerdp/config.h>

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <freerdp/freerdp.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/client.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cliprdr.h>
#include <freerdp/channels/channels.h>
#include <freerdp/channels/cliprdr.h>
#include <freerdp/codec/color.h>
#include <freerdp/event.h>
#include <freerdp/input.h>
#include <freerdp/locale/keyboard.h>
#include <freerdp/version.h>

#include <winpr/crt.h>
#include <winpr/synch.h>
#include <winpr/thread.h>
#include <winpr/string.h>
#include <winpr/input.h>

#define LOG_TAG "TerminowRdp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* The states of a connection, the same as TerminowRdpSession.State in Java */
enum { STATE_CONNECTING = 0, STATE_CONNECTED = 1, STATE_RECONNECTING = 2, STATE_DISCONNECTED = 3, STATE_FAILED = 4 };

/* Input that the UI queued for the client thread */
enum { INPUT_MOUSE = 1, INPUT_KEY = 2, INPUT_UNICODE = 3, INPUT_CLIPBOARD = 4 };

typedef struct InputEvent
{
	int type;
	UINT16 flags;
	UINT16 a;
	UINT16 b;
	WCHAR* text;
	size_t textLength;
	struct InputEvent* next;
} InputEvent;

typedef struct
{
	rdpClientContext common;
	struct TerminowRdp* rdp;
} TerminowContext;

typedef struct TerminowRdp
{
	rdpContext* context;
	jobject callbacks;
	jobject bitmap;
	HANDLE thread;
	HANDLE inputEvent;
	pthread_mutex_t lock;
	InputEvent* head;
	InputEvent* tail;
	CliprdrClientContext* cliprdr;
	WCHAR* localText;
	size_t localTextLength;
	BOOL clipboard;
	volatile BOOL stopping;
	volatile BOOL connected;
} TerminowRdp;

/* Set by JNI_OnLoad of WinPR, which is the one of this library */
extern JavaVM* jniVm;
#define sVm jniVm
static jmethodID sOnResize;
static jmethodID sOnUpdate;
static jmethodID sOnState;
static jmethodID sOnVerifyCertificate;
static jmethodID sOnAuthenticate;
static jmethodID sOnRemoteClipboard;

static JNIEnv* env_of_thread(void)
{
	JNIEnv* env = NULL;
	if ((*sVm)->GetEnv(sVm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
	if ((*sVm)->AttachCurrentThread(sVm, &env, NULL) != JNI_OK) return NULL;
	return env;
}

static TerminowRdp* rdp_of(rdpContext* context)
{
	return context ? ((TerminowContext*)context)->rdp : NULL;
}

static jstring new_string(JNIEnv* env, const char* text)
{
	return (*env)->NewStringUTF(env, text ? text : "");
}

static void report_state(TerminowRdp* rdp, int state, const char* message)
{
	JNIEnv* env = env_of_thread();
	if (!env) return;
	jstring jmessage = new_string(env, message);
	(*env)->CallVoidMethod(env, rdp->callbacks, sOnState, state, jmessage);
	(*env)->DeleteLocalRef(env, jmessage);
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ---------------------------------------------------------------------------------------- */
/* The desktop */

/* A new Bitmap from the app, of the size of the desktop */
static BOOL new_bitmap(TerminowRdp* rdp, UINT32 width, UINT32 height)
{
	JNIEnv* env = env_of_thread();
	if (!env) return FALSE;
	jobject bitmap = (*env)->CallObjectMethod(env, rdp->callbacks, sOnResize, (jint)width, (jint)height);
	if ((*env)->ExceptionCheck(env) || !bitmap)
	{
		(*env)->ExceptionClear(env);
		return FALSE;
	}
	jobject global = (*env)->NewGlobalRef(env, bitmap);
	(*env)->DeleteLocalRef(env, bitmap);
	if (rdp->bitmap) (*env)->DeleteGlobalRef(env, rdp->bitmap);
	rdp->bitmap = global;
	return TRUE;
}

/* Copy the part of the desktop that changed into the Bitmap, then tell the app */
static void copy_to_bitmap(TerminowRdp* rdp, rdpGdi* gdi, INT32 x, INT32 y, INT32 w, INT32 h)
{
	JNIEnv* env = env_of_thread();
	if (!env || !rdp->bitmap || w <= 0 || h <= 0) return;
	AndroidBitmapInfo info;
	void* pixels = NULL;
	if (AndroidBitmap_getInfo(env, rdp->bitmap, &info) < 0) return;
	if ((UINT32)x >= info.width || (UINT32)y >= info.height) return;
	if ((UINT32)(x + w) > info.width) w = (INT32)info.width - x;
	if ((UINT32)(y + h) > info.height) h = (INT32)info.height - y;
	if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
	if (AndroidBitmap_lockPixels(env, rdp->bitmap, &pixels) < 0) return;
	freerdp_image_copy_no_overlap(pixels, PIXEL_FORMAT_RGBX32, info.stride, (UINT32)x, (UINT32)y, (UINT32)w, (UINT32)h,
	                              gdi->primary_buffer, gdi->dstFormat, gdi->stride, (UINT32)x, (UINT32)y, &gdi->palette,
	                              FREERDP_FLIP_NONE);
	AndroidBitmap_unlockPixels(env, rdp->bitmap);
	(*env)->CallVoidMethod(env, rdp->callbacks, sOnUpdate, x, y, w, h);
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static BOOL terminow_begin_paint(rdpContext* context)
{
	rdpGdi* gdi = context->gdi;
	if (gdi && gdi->primary && gdi->primary->hdc && gdi->primary->hdc->hwnd && gdi->primary->hdc->hwnd->invalid)
		gdi->primary->hdc->hwnd->invalid->null = TRUE;
	return TRUE;
}

static BOOL terminow_end_paint(rdpContext* context)
{
	TerminowRdp* rdp = rdp_of(context);
	rdpGdi* gdi = context->gdi;
	if (!rdp || !gdi || !gdi->primary || !gdi->primary->hdc) return FALSE;
	HGDI_WND hwnd = gdi->primary->hdc->hwnd;
	if (!hwnd || hwnd->ninvalid < 1 || !hwnd->cinvalid) return TRUE;
	INT32 x1 = hwnd->cinvalid[0].x, y1 = hwnd->cinvalid[0].y;
	INT32 x2 = x1 + hwnd->cinvalid[0].w, y2 = y1 + hwnd->cinvalid[0].h;
	for (UINT32 i = 1; i < hwnd->ninvalid; i++)
	{
		x1 = MIN(x1, hwnd->cinvalid[i].x);
		y1 = MIN(y1, hwnd->cinvalid[i].y);
		x2 = MAX(x2, hwnd->cinvalid[i].x + hwnd->cinvalid[i].w);
		y2 = MAX(y2, hwnd->cinvalid[i].y + hwnd->cinvalid[i].h);
	}
	copy_to_bitmap(rdp, gdi, x1, y1, x2 - x1, y2 - y1);
	hwnd->invalid->null = TRUE;
	hwnd->ninvalid = 0;
	return TRUE;
}

static BOOL terminow_desktop_resize(rdpContext* context)
{
	TerminowRdp* rdp = rdp_of(context);
	UINT32 width = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
	UINT32 height = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
	if (context->gdi && !gdi_resize(context->gdi, width, height)) return FALSE;
	if (!rdp || !new_bitmap(rdp, width, height)) return FALSE;
	copy_to_bitmap(rdp, context->gdi, 0, 0, (INT32)width, (INT32)height);
	return TRUE;
}

/* ---------------------------------------------------------------------------------------- */
/* Text clipboard, when the host has it turned on */

static UINT clip_send_format_list(TerminowRdp* rdp)
{
	if (!rdp->cliprdr) return CHANNEL_RC_OK;
	CLIPRDR_FORMAT format = { CF_UNICODETEXT, NULL };
	CLIPRDR_FORMAT_LIST list = { 0 };
	list.common.msgType = CB_FORMAT_LIST;
	list.numFormats = rdp->localText ? 1 : 0;
	list.formats = rdp->localText ? &format : NULL;
	return rdp->cliprdr->ClientFormatList(rdp->cliprdr, &list);
}

static UINT clip_monitor_ready(CliprdrClientContext* cliprdr, const CLIPRDR_MONITOR_READY* ready)
{
	(void)ready;
	TerminowRdp* rdp = (TerminowRdp*)cliprdr->custom;
	CLIPRDR_GENERAL_CAPABILITY_SET general = { 0 };
	general.capabilitySetType = CB_CAPSTYPE_GENERAL;
	general.capabilitySetLength = CB_CAPSTYPE_GENERAL_LEN;
	general.version = CB_CAPS_VERSION_2;
	general.generalFlags = CB_USE_LONG_FORMAT_NAMES;
	CLIPRDR_CAPABILITIES caps = { 0 };
	caps.cCapabilitiesSets = 1;
	caps.capabilitySets = (CLIPRDR_CAPABILITY_SET*)&general;
	UINT rc = cliprdr->ClientCapabilities(cliprdr, &caps);
	if (rc != CHANNEL_RC_OK) return rc;
	return clip_send_format_list(rdp);
}

static UINT clip_server_capabilities(CliprdrClientContext* cliprdr, const CLIPRDR_CAPABILITIES* caps)
{
	(void)cliprdr;
	(void)caps;
	return CHANNEL_RC_OK;
}

static UINT clip_server_format_list(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_LIST* list)
{
	CLIPRDR_FORMAT_LIST_RESPONSE response = { 0 };
	response.common.msgType = CB_FORMAT_LIST_RESPONSE;
	response.common.msgFlags = CB_RESPONSE_OK;
	UINT rc = cliprdr->ClientFormatListResponse(cliprdr, &response);
	if (rc != CHANNEL_RC_OK) return rc;
	for (UINT32 i = 0; i < list->numFormats; i++)
	{
		if (list->formats[i].formatId == CF_UNICODETEXT)
		{
			CLIPRDR_FORMAT_DATA_REQUEST request = { 0 };
			request.common.msgType = CB_FORMAT_DATA_REQUEST;
			request.requestedFormatId = CF_UNICODETEXT;
			return cliprdr->ClientFormatDataRequest(cliprdr, &request);
		}
	}
	return CHANNEL_RC_OK;
}

static UINT clip_server_format_list_response(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_LIST_RESPONSE* response)
{
	(void)cliprdr;
	(void)response;
	return CHANNEL_RC_OK;
}

static UINT clip_server_format_data_request(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_DATA_REQUEST* request)
{
	TerminowRdp* rdp = (TerminowRdp*)cliprdr->custom;
	CLIPRDR_FORMAT_DATA_RESPONSE response = { 0 };
	response.common.msgType = CB_FORMAT_DATA_RESPONSE;
	if (request->requestedFormatId == CF_UNICODETEXT && rdp->localText)
	{
		response.common.msgFlags = CB_RESPONSE_OK;
		response.common.dataLen = (UINT32)((rdp->localTextLength + 1) * sizeof(WCHAR));
		response.requestedFormatData = (const BYTE*)rdp->localText;
	}
	else
	{
		response.common.msgFlags = CB_RESPONSE_FAIL;
	}
	return cliprdr->ClientFormatDataResponse(cliprdr, &response);
}

static UINT clip_server_format_data_response(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_DATA_RESPONSE* response)
{
	TerminowRdp* rdp = (TerminowRdp*)cliprdr->custom;
	if (!(response->common.msgFlags & CB_RESPONSE_OK) || !response->requestedFormatData) return CHANNEL_RC_OK;
	size_t chars = response->common.dataLen / sizeof(WCHAR);
	size_t size = 0;
	char* utf8 = ConvertWCharNToUtf8Alloc((const WCHAR*)response->requestedFormatData, chars, &size);
	if (!utf8) return CHANNEL_RC_OK;
	JNIEnv* env = env_of_thread();
	if (env)
	{
		jstring text = (*env)->NewStringUTF(env, utf8);
		(*env)->CallVoidMethod(env, rdp->callbacks, sOnRemoteClipboard, text);
		(*env)->DeleteLocalRef(env, text);
		if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
	}
	free(utf8);
	return CHANNEL_RC_OK;
}

static void on_channel_connected(void* context, const ChannelConnectedEventArgs* e)
{
	TerminowRdp* rdp = rdp_of((rdpContext*)context);
	if (rdp && strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
	{
		CliprdrClientContext* cliprdr = (CliprdrClientContext*)e->pInterface;
		cliprdr->custom = rdp;
		cliprdr->MonitorReady = clip_monitor_ready;
		cliprdr->ServerCapabilities = clip_server_capabilities;
		cliprdr->ServerFormatList = clip_server_format_list;
		cliprdr->ServerFormatListResponse = clip_server_format_list_response;
		cliprdr->ServerFormatDataRequest = clip_server_format_data_request;
		cliprdr->ServerFormatDataResponse = clip_server_format_data_response;
		rdp->cliprdr = cliprdr;
		return;
	}
	freerdp_client_OnChannelConnectedEventHandler(context, e);
}

static void on_channel_disconnected(void* context, const ChannelDisconnectedEventArgs* e)
{
	TerminowRdp* rdp = rdp_of((rdpContext*)context);
	if (rdp && strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
	{
		rdp->cliprdr = NULL;
		return;
	}
	freerdp_client_OnChannelDisconnectedEventHandler(context, e);
}

/* ---------------------------------------------------------------------------------------- */
/* Connecting */

static BOOL terminow_pre_connect(freerdp* instance)
{
	rdpContext* context = instance->context;
	if (PubSub_SubscribeChannelConnected(context->pubSub, on_channel_connected) < 0) return FALSE;
	if (PubSub_SubscribeChannelDisconnected(context->pubSub, on_channel_disconnected) < 0) return FALSE;
	return TRUE;
}

static BOOL terminow_post_connect(freerdp* instance)
{
	rdpContext* context = instance->context;
	TerminowRdp* rdp = rdp_of(context);
	if (!gdi_init(instance, PIXEL_FORMAT_RGBX32)) return FALSE;
	context->update->BeginPaint = terminow_begin_paint;
	context->update->EndPaint = terminow_end_paint;
	context->update->DesktopResize = terminow_desktop_resize;
	UINT32 width = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
	UINT32 height = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
	if (!rdp || !new_bitmap(rdp, width, height)) return FALSE;
	rdp->connected = TRUE;
	report_state(rdp, STATE_CONNECTED, "");
	return TRUE;
}

static void terminow_post_disconnect(freerdp* instance)
{
	gdi_free(instance);
}

static void terminow_post_final_disconnect(freerdp* instance)
{
	PubSub_UnsubscribeChannelConnected(instance->context->pubSub, on_channel_connected);
	PubSub_UnsubscribeChannelDisconnected(instance->context->pubSub, on_channel_disconnected);
}

/* Read a string of the array that the app answered, a copy for FreeRDP, NULL if it is empty */
static char* string_at(JNIEnv* env, jobjectArray array, jsize index)
{
	jstring value = (jstring)(*env)->GetObjectArrayElement(env, array, index);
	if (!value) return NULL;
	const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
	char* copy = chars && chars[0] ? _strdup(chars) : NULL;
	if (chars) (*env)->ReleaseStringUTFChars(env, value, chars);
	(*env)->DeleteLocalRef(env, value);
	return copy;
}

static BOOL terminow_authenticate(freerdp* instance, char** username, char** password, char** domain, rdp_auth_reason reason)
{
	/* A gateway is not supported: its credentials are never asked for */
	if (reason != AUTH_NLA && reason != AUTH_TLS && reason != AUTH_RDP) return FALSE;
	TerminowRdp* rdp = rdp_of(instance->context);
	JNIEnv* env = env_of_thread();
	if (!rdp || !env) return FALSE;
	jstring juser = new_string(env, *username);
	jstring jdomain = new_string(env, *domain);
	jobjectArray answer = (jobjectArray)(*env)->CallObjectMethod(env, rdp->callbacks, sOnAuthenticate, juser, jdomain, (jint)reason);
	(*env)->DeleteLocalRef(env, juser);
	(*env)->DeleteLocalRef(env, jdomain);
	if ((*env)->ExceptionCheck(env) || !answer || (*env)->GetArrayLength(env, answer) < 3)
	{
		(*env)->ExceptionClear(env);
		return FALSE;
	}
	free(*username);
	free(*domain);
	if (*password)
	{
		memset(*password, 0, strlen(*password));
		free(*password);
	}
	*username = string_at(env, answer, 0);
	*domain = string_at(env, answer, 1);
	*password = string_at(env, answer, 2);
	(*env)->DeleteLocalRef(env, answer);
	return *username != NULL;
}

static DWORD verify(freerdp* instance, const char* host, UINT16 port, const char* common_name, const char* subject,
                    const char* issuer, const char* fingerprint, BOOL changed, const char* old_subject,
                    const char* old_issuer, const char* old_fingerprint, DWORD flags)
{
	TerminowRdp* rdp = rdp_of(instance->context);
	JNIEnv* env = env_of_thread();
	if (!rdp || !env) return 0;
	jstring args[8] = { new_string(env, host), new_string(env, common_name), new_string(env, subject),
		                new_string(env, issuer), new_string(env, fingerprint), new_string(env, old_subject),
		                new_string(env, old_issuer), new_string(env, old_fingerprint) };
	jint answer = (*env)->CallIntMethod(env, rdp->callbacks, sOnVerifyCertificate, args[0], (jint)port, args[1], args[2],
	                                    args[3], args[4], (jboolean)changed, args[5], args[6], args[7], (jint)flags);
	for (int i = 0; i < 8; i++) (*env)->DeleteLocalRef(env, args[i]);
	if ((*env)->ExceptionCheck(env))
	{
		(*env)->ExceptionClear(env);
		return 0;
	}
	/* 1: accept and remember, 2: accept for this connection only, anything else: refuse */
	return answer == 1 || answer == 2 ? (DWORD)answer : 0;
}

static DWORD terminow_verify_certificate(freerdp* instance, const char* host, UINT16 port, const char* common_name,
                                         const char* subject, const char* issuer, const char* fingerprint, DWORD flags)
{
	return verify(instance, host, port, common_name, subject, issuer, fingerprint, FALSE, "", "", "", flags);
}

static DWORD terminow_verify_changed_certificate(freerdp* instance, const char* host, UINT16 port, const char* common_name,
                                                 const char* subject, const char* issuer, const char* new_fingerprint,
                                                 const char* old_subject, const char* old_issuer,
                                                 const char* old_fingerprint, DWORD flags)
{
	return verify(instance, host, port, common_name, subject, issuer, new_fingerprint, TRUE, old_subject, old_issuer,
	              old_fingerprint, flags);
}

static BOOL terminow_client_new(freerdp* instance, rdpContext* context)
{
	(void)context;
	instance->PreConnect = terminow_pre_connect;
	instance->PostConnect = terminow_post_connect;
	instance->PostDisconnect = terminow_post_disconnect;
	instance->PostFinalDisconnect = terminow_post_final_disconnect;
	instance->AuthenticateEx = terminow_authenticate;
	instance->VerifyCertificateEx = terminow_verify_certificate;
	instance->VerifyChangedCertificateEx = terminow_verify_changed_certificate;
	return TRUE;
}

static void terminow_client_free(freerdp* instance, rdpContext* context)
{
	(void)instance;
	(void)context;
}

static int client_entry(RDP_CLIENT_ENTRY_POINTS* entry)
{
	ZeroMemory(entry, sizeof(RDP_CLIENT_ENTRY_POINTS));
	entry->Version = RDP_CLIENT_INTERFACE_VERSION;
	entry->Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
	entry->ContextSize = sizeof(TerminowContext);
	entry->ClientNew = terminow_client_new;
	entry->ClientFree = terminow_client_free;
	return 0;
}

/* ---------------------------------------------------------------------------------------- */
/* Input */

static void push_input(TerminowRdp* rdp, InputEvent* event)
{
	pthread_mutex_lock(&rdp->lock);
	if (rdp->tail) rdp->tail->next = event;
	else rdp->head = event;
	rdp->tail = event;
	pthread_mutex_unlock(&rdp->lock);
	SetEvent(rdp->inputEvent);
}

/* Send what the UI queued, on the client thread */
static void send_input(TerminowRdp* rdp)
{
	pthread_mutex_lock(&rdp->lock);
	InputEvent* event = rdp->head;
	rdp->head = rdp->tail = NULL;
	ResetEvent(rdp->inputEvent);
	pthread_mutex_unlock(&rdp->lock);
	rdpInput* input = rdp->context->input;
	while (event)
	{
		InputEvent* next = event->next;
		if (rdp->connected)
		{
			switch (event->type)
			{
				case INPUT_MOUSE:
					freerdp_input_send_mouse_event(input, event->flags, event->a, event->b);
					break;
				case INPUT_KEY:
					freerdp_input_send_keyboard_event(input, event->flags, (UINT8)event->a);
					break;
				case INPUT_UNICODE:
					freerdp_input_send_unicode_keyboard_event(input, event->flags, event->a);
					break;
				case INPUT_CLIPBOARD:
					free(rdp->localText);
					rdp->localText = event->text;
					rdp->localTextLength = event->textLength;
					event->text = NULL;
					clip_send_format_list(rdp);
					break;
				default:
					break;
			}
		}
		free(event->text);
		free(event);
		event = next;
	}
}

/* ---------------------------------------------------------------------------------------- */
/* The client thread */

static void error_message(rdpContext* context, char* out, size_t size)
{
	UINT32 code = freerdp_get_last_error(context);
	const char* name = freerdp_get_last_error_name(code);
	const char* text = freerdp_get_last_error_string(code);
	snprintf(out, size, "%s|%s", name ? name : "", text ? text : "");
}

static DWORD WINAPI client_thread(LPVOID arg)
{
	TerminowRdp* rdp = (TerminowRdp*)arg;
	rdpContext* context = rdp->context;
	freerdp* instance = context->instance;
	char message[512] = { 0 };
	env_of_thread();
	report_state(rdp, STATE_CONNECTING, "");

	if (freerdp_client_start(context) != CHANNEL_RC_OK || !freerdp_connect(instance))
	{
		error_message(context, message, sizeof(message));
		freerdp_client_stop(context);
		report_state(rdp, rdp->stopping ? STATE_DISCONNECTED : STATE_FAILED, message);
		(*sVm)->DetachCurrentThread(sVm);
		return 1;
	}

	HANDLE handles[MAXIMUM_WAIT_OBJECTS];
	while (!freerdp_shall_disconnect_context(context) && !rdp->stopping)
	{
		DWORD count = 0;
		handles[count++] = rdp->inputEvent;
		DWORD more = freerdp_get_event_handles(context, &handles[count], ARRAYSIZE(handles) - count);
		if (more == 0) break;
		count += more;
		if (WaitForMultipleObjects(count, handles, FALSE, INFINITE) == WAIT_FAILED) break;
		if (rdp->stopping) break;
		if (!freerdp_check_event_handles(context))
		{
			/* The connection broke, like when the network changed: FreeRDP tries again */
			if (rdp->stopping) break;
			rdp->connected = FALSE;
			report_state(rdp, STATE_RECONNECTING, "");
			if (!client_auto_reconnect(instance)) break;
			rdp->connected = TRUE;
			report_state(rdp, STATE_CONNECTED, "");
		}
		if (WaitForSingleObject(rdp->inputEvent, 0) == WAIT_OBJECT_0) send_input(rdp);
	}

	rdp->connected = FALSE;
	BOOL failed = !rdp->stopping && freerdp_get_last_error(context) != FREERDP_ERROR_SUCCESS;
	if (failed) error_message(context, message, sizeof(message));
	freerdp_disconnect(instance);
	freerdp_client_stop(context);
	report_state(rdp, failed ? STATE_FAILED : STATE_DISCONNECTED, message);
	(*sVm)->DetachCurrentThread(sVm);
	return 0;
}

/* ---------------------------------------------------------------------------------------- */
/* JNI */

#define NATIVE(name) Java_com_termux_app_terminow_rdp_TerminowRdpNative_##name

static char* utf8_copy(JNIEnv* env, jstring value)
{
	if (!value) return NULL;
	const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
	char* copy = chars ? _strdup(chars) : NULL;
	if (chars) (*env)->ReleaseStringUTFChars(env, value, chars);
	return copy;
}

static BOOL set_string(JNIEnv* env, rdpSettings* settings, FreeRDP_Settings_Keys_String key, jstring value)
{
	char* copy = utf8_copy(env, value);
	if (!copy || !copy[0])
	{
		free(copy);
		return TRUE;
	}
	BOOL ok = freerdp_settings_set_string(settings, key, copy);
	memset(copy, 0, strlen(copy));
	free(copy);
	return ok;
}

JNIEXPORT jlong JNICALL NATIVE(nativeCreate)(JNIEnv* env, jclass cls, jobject callbacks)
{
	(void)cls;
	if (!sOnResize)
	{
		jclass type = (*env)->GetObjectClass(env, callbacks);
		sOnResize = (*env)->GetMethodID(env, type, "onResize", "(II)Landroid/graphics/Bitmap;");
		sOnUpdate = (*env)->GetMethodID(env, type, "onUpdate", "(IIII)V");
		sOnState = (*env)->GetMethodID(env, type, "onState", "(ILjava/lang/String;)V");
		sOnVerifyCertificate = (*env)->GetMethodID(env, type, "onVerifyCertificate",
		    "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z"
		    "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I");
		sOnAuthenticate = (*env)->GetMethodID(env, type, "onAuthenticate", "(Ljava/lang/String;Ljava/lang/String;I)[Ljava/lang/String;");
		sOnRemoteClipboard = (*env)->GetMethodID(env, type, "onRemoteClipboard", "(Ljava/lang/String;)V");
		if (!sOnResize || !sOnUpdate || !sOnState || !sOnVerifyCertificate || !sOnAuthenticate || !sOnRemoteClipboard)
			return 0;
	}
	TerminowRdp* rdp = calloc(1, sizeof(TerminowRdp));
	if (!rdp) return 0;
	RDP_CLIENT_ENTRY_POINTS entry;
	client_entry(&entry);
	rdp->context = freerdp_client_context_new(&entry);
	if (!rdp->context)
	{
		free(rdp);
		return 0;
	}
	((TerminowContext*)rdp->context)->rdp = rdp;
	rdp->callbacks = (*env)->NewGlobalRef(env, callbacks);
	rdp->inputEvent = CreateEvent(NULL, TRUE, FALSE, NULL);
	pthread_mutex_init(&rdp->lock, NULL);
	return (jlong)(intptr_t)rdp;
}

JNIEXPORT jboolean JNICALL NATIVE(nativeConfigure)(JNIEnv* env, jclass cls, jlong handle, jstring host, jint port,
                                                   jstring username, jstring domain, jstring password, jint width,
                                                   jint height, jboolean nla, jboolean clipboard, jstring configDir)
{
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	if (!rdp) return JNI_FALSE;
	rdpSettings* settings = rdp->context->settings;
	BOOL ok = set_string(env, settings, FreeRDP_ServerHostname, host) &&
	          freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, (UINT32)port) &&
	          set_string(env, settings, FreeRDP_Username, username) && set_string(env, settings, FreeRDP_Domain, domain) &&
	          set_string(env, settings, FreeRDP_Password, password) &&
	          set_string(env, settings, FreeRDP_ConfigPath, configDir) &&
	          freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, (UINT32)width) &&
	          freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, (UINT32)height) &&
	          freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32) &&
	          /* TLS always; NLA when the host asks for it; never the old RDP security */
	          freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, nla ? TRUE : FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, TRUE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_ExtSecurity, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_AutoAcceptCertificate, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_AutoDenyCertificate, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_AutoReconnectionEnabled, TRUE) &&
	          freerdp_settings_set_uint32(settings, FreeRDP_AutoReconnectMaxRetries, 5) &&
	          freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, clipboard ? TRUE : FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, TRUE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_GfxH264, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, FALSE) &&
	          freerdp_settings_set_bool(settings, FreeRDP_NetworkAutoDetect, TRUE) &&
	          freerdp_settings_set_uint32(settings, FreeRDP_KeyboardLayout, 0x00000409) &&
	          freerdp_settings_set_bool(settings, FreeRDP_UnicodeInput, TRUE);
	rdp->clipboard = clipboard ? TRUE : FALSE;
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL NATIVE(nativeStart)(JNIEnv* env, jclass cls, jlong handle)
{
	(void)env;
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	if (!rdp || rdp->thread) return JNI_FALSE;
	rdp->thread = CreateThread(NULL, 0, client_thread, rdp, 0, NULL);
	return rdp->thread ? JNI_TRUE : JNI_FALSE;
}

/* Disconnect from any thread: the client thread ends and says so with onState */
JNIEXPORT void JNICALL NATIVE(nativeStop)(JNIEnv* env, jclass cls, jlong handle)
{
	(void)env;
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	if (!rdp) return;
	rdp->stopping = TRUE;
	freerdp_abort_connect_context(rdp->context);
	SetEvent(rdp->inputEvent);
}

/* Free a connection whose client thread ended, or was never started */
JNIEXPORT void JNICALL NATIVE(nativeFree)(JNIEnv* env, jclass cls, jlong handle)
{
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	if (!rdp) return;
	if (rdp->thread)
	{
		WaitForSingleObject(rdp->thread, INFINITE);
		CloseHandle(rdp->thread);
	}
	freerdp_client_context_free(rdp->context);
	pthread_mutex_lock(&rdp->lock);
	InputEvent* event = rdp->head;
	rdp->head = rdp->tail = NULL;
	pthread_mutex_unlock(&rdp->lock);
	while (event)
	{
		InputEvent* next = event->next;
		free(event->text);
		free(event);
		event = next;
	}
	free(rdp->localText);
	if (rdp->bitmap) (*env)->DeleteGlobalRef(env, rdp->bitmap);
	(*env)->DeleteGlobalRef(env, rdp->callbacks);
	CloseHandle(rdp->inputEvent);
	pthread_mutex_destroy(&rdp->lock);
	free(rdp);
}

JNIEXPORT void JNICALL NATIVE(nativeSendMouse)(JNIEnv* env, jclass cls, jlong handle, jint flags, jint x, jint y)
{
	(void)env;
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	InputEvent* event = rdp ? calloc(1, sizeof(InputEvent)) : NULL;
	if (!event) return;
	event->type = INPUT_MOUSE;
	event->flags = (UINT16)flags;
	event->a = (UINT16)MAX(0, x);
	event->b = (UINT16)MAX(0, y);
	push_input(rdp, event);
}

/* A key by its Windows virtual key code, which is turned into its scan code here */
JNIEXPORT void JNICALL NATIVE(nativeSendKey)(JNIEnv* env, jclass cls, jlong handle, jint virtualKey, jboolean down)
{
	(void)env;
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	DWORD scancode = GetVirtualScanCodeFromVirtualKeyCode((DWORD)virtualKey, WINPR_KBD_TYPE_IBM_ENHANCED);
	if (!rdp || scancode == 0) return;
	InputEvent* event = calloc(1, sizeof(InputEvent));
	if (!event) return;
	event->type = INPUT_KEY;
	event->flags = (UINT16)((down ? KBD_FLAGS_DOWN : KBD_FLAGS_RELEASE) | ((scancode & KBDEXT) ? KBD_FLAGS_EXTENDED : 0));
	event->a = (UINT16)(scancode & 0xFF);
	push_input(rdp, event);
}

JNIEXPORT void JNICALL NATIVE(nativeSendUnicode)(JNIEnv* env, jclass cls, jlong handle, jint codeUnit, jboolean down)
{
	(void)env;
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	InputEvent* event = rdp ? calloc(1, sizeof(InputEvent)) : NULL;
	if (!event) return;
	event->type = INPUT_UNICODE;
	event->flags = (UINT16)(down ? 0 : KBD_FLAGS_RELEASE);
	event->a = (UINT16)codeUnit;
	push_input(rdp, event);
}

/* The text of the Android clipboard, offered to Windows; only when the host has the clipboard on */
JNIEXPORT void JNICALL NATIVE(nativeSetClipboard)(JNIEnv* env, jclass cls, jlong handle, jstring text)
{
	(void)cls;
	TerminowRdp* rdp = (TerminowRdp*)(intptr_t)handle;
	if (!rdp || !rdp->clipboard || !text) return;
	jsize length = (*env)->GetStringLength(env, text);
	if (length > 1024 * 1024) return;
	InputEvent* event = calloc(1, sizeof(InputEvent));
	WCHAR* copy = calloc((size_t)length + 1, sizeof(WCHAR));
	if (!event || !copy)
	{
		free(event);
		free(copy);
		return;
	}
	(*env)->GetStringRegion(env, text, 0, length, (jchar*)copy);
	event->type = INPUT_CLIPBOARD;
	event->text = copy;
	event->textLength = (size_t)length;
	push_input(rdp, event);
}

JNIEXPORT jstring JNICALL NATIVE(nativeVersion)(JNIEnv* env, jclass cls)
{
	(void)cls;
	return (*env)->NewStringUTF(env, freerdp_get_version_string());
}
