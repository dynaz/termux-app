'use strict';
// Firestore emulator helper: seed the authoritative whmcsLinks mapping and other sources, read back
// entitlements/{uid}, or reset. Uses firebase-admin against the emulator (same SDK as the writer).
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
initializeApp({ projectId: process.env.GCLOUD_PROJECT || 'demo-terminow' });
const db = getFirestore();

async function main() {
  const [cmd, a, b] = process.argv.slice(2);
  if (cmd === 'seed-link') { await db.collection('whmcsLinks').doc(a).set({ clientId: Number(b) }); console.log('linked ' + a + ' -> client ' + b); }
  else if (cmd === 'seed-source') { // seed-source <uid> <sourceName> <json>
    const uid = a, source = b, data = JSON.parse(process.argv[5]);
    const ref = db.collection('entitlements').doc(uid);
    const snap = await ref.get();
    const cur = snap.exists ? snap.data() : { sources: {}, premium: false };
    cur.sources = cur.sources || {};
    cur.sources[source] = data;
    cur.premium = Object.values(cur.sources).some((s) => s && s.premium === true);
    await ref.set(cur);
    console.log('seeded source ' + source + ' on ' + uid);
  }
  else if (cmd === 'read') { const snap = await db.collection('entitlements').doc(a).get(); console.log(JSON.stringify(snap.exists ? snap.data() : null, null, 2)); }
  else if (cmd === 'read-delivery') { const snap = await db.collection('whmcsDeliveries').doc(a).get(); console.log(JSON.stringify(snap.exists ? snap.data() : null, null, 2)); }
  else if (cmd === 'reset') {
    for (const col of ['entitlements', 'whmcsLinks', 'whmcsDeliveries']) {
      const qs = await db.collection(col).get();
      for (const d of qs.docs) await d.ref.delete();
    }
    console.log('emulator reset');
  }
  else { console.error('unknown cmd ' + cmd); process.exit(2); }
}
main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
