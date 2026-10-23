package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The MCP servers of the Browse tab. Every endpoint, transport and sign-in method here was read
 * from the documentation of the provider, linked in {@link Entry#docsUrl}, on {@link #CHECKED};
 * nothing is guessed. An entry whose endpoint the documentation does not give exactly has no
 * {@link Entry#url} and is set up by hand. Local servers run a command and are listed with it,
 * but TermiNow does not run or test them.
 */
public final class TerminowMcpCatalog {

    /** When the entries were checked against the documentation of each provider. */
    public static final String CHECKED = "2026-10-14";

    public static final String CATEGORY_ALL = "all";
    public static final String CATEGORY_DEVELOPER = "developer";
    public static final String CATEGORY_PRODUCTIVITY = "productivity";
    public static final String CATEGORY_BUSINESS = "business";
    public static final String CATEGORY_DATA = "data";
    public static final String CATEGORY_CUSTOM = "custom";

    public static final List<String> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
        CATEGORY_ALL, CATEGORY_DEVELOPER, CATEGORY_PRODUCTIVITY, CATEGORY_BUSINESS, CATEGORY_DATA, CATEGORY_CUSTOM));

    /** How a server is reached. */
    public enum Kind {
        /** A remote server over Streamable HTTP, with the URL from its documentation. */
        REMOTE,
        /** A remote server whose documentation does not give the exact URL: set up by hand. */
        REMOTE_MANUAL,
        /** A local server that runs a command over stdio. */
        LOCAL
    }

    public static final class Entry {
        public final String id;
        public final String name;
        public final String publisher;
        public final String description;
        public final String category;
        public final Kind kind;
        /** The Streamable HTTP endpoint, only for {@link Kind#REMOTE}. */
        @Nullable public final String url;
        /** How to sign in, as the documentation says it. */
        public final String auth;
        /** Whether a bearer token in the Authorization header is documented, besides OAuth. */
        public final boolean bearerToken;
        /** Whether OAuth is documented, which TermiNow does not do itself. */
        public final boolean oauth;
        /** For {@link Kind#LOCAL}, the documented command and its arguments. */
        public final List<String> command;
        /** Something to know, like a client allow list, empty if nothing. */
        public final String note;
        /** The official page the entry was checked against. */
        public final String docsUrl;
        /** A color for the letter icon. */
        public final int color;

        Entry(String id, String name, String publisher, String description, String category, Kind kind, @Nullable String url,
              String auth, boolean oauth, boolean bearerToken, List<String> command, String note, String docsUrl, int color) {
            this.id = id;
            this.name = name;
            this.publisher = publisher;
            this.description = description;
            this.category = category;
            this.kind = kind;
            this.url = url;
            this.auth = auth;
            this.oauth = oauth;
            this.bearerToken = bearerToken;
            this.command = command;
            this.note = note;
            this.docsUrl = docsUrl;
            this.color = color;
        }

        /** Whether Add Server can fill in a verified endpoint, which TermiNow can test. */
        public boolean canAdd() {
            return kind == Kind.REMOTE && url != null;
        }

        /** A name for the saved server: letters, digits, - and _, as Claude Code requires. */
        @NonNull
        public String suggestedName() {
            return id;
        }
    }

    private static Entry remote(String id, String name, String publisher, String description, String category, String url,
                                String auth, boolean oauth, boolean bearer, String note, String docs, int color) {
        return new Entry(id, name, publisher, description, category, Kind.REMOTE, url, auth, oauth, bearer,
            Collections.emptyList(), note, docs, color);
    }

    public static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
        // Developer tools
        remote("github", "GitHub", "GitHub", "Repositories, issues, pull requests and Actions",
            CATEGORY_DEVELOPER, "https://api.githubcopilot.com/mcp/",
            "OAuth, or a personal access token as a bearer token", true, true, "",
            "https://github.com/github/github-mcp-server", 0xFF24292F),
        remote("sentry", "Sentry", "Sentry", "Errors, issues and performance of your projects",
            CATEGORY_DEVELOPER, "https://mcp.sentry.dev/mcp",
            "OAuth", true, false, "The URL can be scoped to an organization or project: /mcp/{organization}/{project}",
            "https://mcp.sentry.dev/", 0xFF362D59),
        remote("vercel", "Vercel", "Vercel", "Projects, deployments, logs and documentation",
            CATEGORY_DEVELOPER, "https://mcp.vercel.com",
            "OAuth", true, false, "Vercel only lets AI clients that it approved sign in, like Claude Code and Codex CLI",
            "https://vercel.com/docs/agent-resources/vercel-mcp", 0xFF000000),
        remote("cloudflare", "Cloudflare", "Cloudflare", "Your Cloudflare account and its API",
            CATEGORY_DEVELOPER, "https://mcp.cloudflare.com/mcp",
            "OAuth, or an API token as a bearer token", true, true, "",
            "https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/", 0xFFF38020),
        remote("cloudflare-docs", "Cloudflare Docs", "Cloudflare", "Search the Cloudflare documentation",
            CATEGORY_DEVELOPER, "https://docs.mcp.cloudflare.com/mcp",
            "The documentation does not state any", false, false, "",
            "https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/", 0xFFF38020),
        remote("context7", "Context7", "Upstash", "Up-to-date documentation of libraries for coding",
            CATEGORY_DEVELOPER, "https://mcp.context7.com/mcp",
            "None, an API key as a bearer token is optional for higher limits", false, true, "",
            "https://github.com/upstash/context7", 0xFF059669),
        remote("deepwiki", "DeepWiki", "Cognition", "Ask about public GitHub repositories",
            CATEGORY_DEVELOPER, "https://mcp.deepwiki.com/mcp",
            "None", false, false, "Only for public repositories",
            "https://docs.devin.ai/work-with-devin/deepwiki-mcp", 0xFF2563EB),
        remote("figma", "Figma", "Figma", "Design files for code",
            CATEGORY_DEVELOPER, "https://mcp.figma.com/mcp",
            "The guide does not describe it", false, false, "",
            "https://help.figma.com/hc/en-us/articles/32132100833559-Guide-to-the-Figma-MCP-server", 0xFFA259FF),
        // Productivity
        remote("linear", "Linear", "Linear", "Issues, projects and cycles",
            CATEGORY_PRODUCTIVITY, "https://mcp.linear.app/mcp",
            "OAuth, or an API key as a bearer token", true, true, "",
            "https://linear.app/docs/mcp", 0xFF5E6AD2),
        remote("notion", "Notion", "Notion", "Pages, databases and search of your workspace",
            CATEGORY_PRODUCTIVITY, "https://mcp.notion.com/mcp",
            "OAuth only", true, false, "",
            "https://developers.notion.com/guides/mcp/get-started-with-mcp", 0xFF191919),
        remote("asana", "Asana", "Asana", "Tasks, projects and goals",
            CATEGORY_PRODUCTIVITY, "https://mcp.asana.com/v2/mcp",
            "OAuth", true, false, "",
            "https://developers.asana.com/docs/using-asanas-mcp-server", 0xFFF06A6A),
        remote("atlassian", "Atlassian", "Atlassian", "Jira and Confluence",
            CATEGORY_PRODUCTIVITY, "https://mcp.atlassian.com/v2/mcp",
            "OAuth 2.1, an API token is optional", true, false, "",
            "https://support.atlassian.com/atlassian-rovo-mcp-server/docs/getting-started-with-the-atlassian-remote-mcp-server/", 0xFF0052CC),
        // Business
        remote("stripe", "Stripe", "Stripe", "Payments, customers, subscriptions and the Stripe docs",
            CATEGORY_BUSINESS, "https://mcp.stripe.com",
            "OAuth, or an agent API key as a bearer token", true, true,
            "Stripe asks for a human confirmation before some actions, like refunds",
            "https://docs.stripe.com/mcp", 0xFF635BFF),
        new Entry("hubspot", "HubSpot", "HubSpot", "CRM records of your HubSpot account",
            CATEGORY_BUSINESS, Kind.REMOTE_MANUAL, null,
            "OAuth 2.0 with an app that you create", true, false, Collections.emptyList(),
            "The documentation names mcp.hubspot.com without the exact URL, so it is not filled in",
            "https://developers.hubspot.com/mcp", 0xFFFF7A59),
        // Data
        remote("supabase", "Supabase", "Supabase", "Your Supabase projects, databases and logs",
            CATEGORY_DATA, "https://mcp.supabase.com/mcp",
            "OAuth, or a personal access token as a bearer token", true, true, "",
            "https://supabase.com/docs/guides/getting-started/mcp", 0xFF3ECF8E),
        remote("neon", "Neon", "Neon", "Serverless Postgres projects and branches",
            CATEGORY_DATA, "https://mcp.neon.tech/mcp",
            "OAuth, or an API key as a bearer token", true, true, "",
            "https://neon.com/docs/ai/neon-mcp-server", 0xFF00E599),
        remote("apify", "Apify", "Apify", "Discover and run Apify Actors and retrieve results",
            CATEGORY_DATA, "https://mcp.apify.com",
            "OAuth, or an API token as a bearer token", true, true, "Web scraping and automation Actors",
            "https://docs.apify.com/platform/integrations/mcp", 0xFF97D700),
        remote("huggingface", "Hugging Face", "Hugging Face", "Models, datasets, Spaces and papers",
            CATEGORY_DATA, "https://huggingface.co/mcp",
            "As your Hugging Face MCP settings page generates it", false, false,
            "Get the exact configuration from huggingface.co/settings/mcp",
            "https://huggingface.co/docs/hub/hf-mcp-server", 0xFFFFD21E),
        // Custom: local reference servers, and any server
        new Entry("filesystem", "Filesystem", "Model Context Protocol", "Read and write the files of folders you allow",
            CATEGORY_CUSTOM, Kind.LOCAL, null, "None, it runs on the device", false, false,
            Arrays.asList("npx", "-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/files"),
            "Needs Node.js in the environment where the AI client runs",
            "https://github.com/modelcontextprotocol/servers", 0xFF64748B),
        new Entry("git", "Git", "Model Context Protocol", "Read and change git repositories",
            CATEGORY_CUSTOM, Kind.LOCAL, null, "None, it runs on the device", false, false,
            Arrays.asList("uvx", "mcp-server-git"),
            "Needs uv (Python) in the environment where the AI client runs",
            "https://github.com/modelcontextprotocol/servers", 0xFF64748B),
        new Entry("memory", "Memory", "Model Context Protocol", "A knowledge graph that the AI client keeps",
            CATEGORY_CUSTOM, Kind.LOCAL, null, "None, it runs on the device", false, false,
            Arrays.asList("npx", "-y", "@modelcontextprotocol/server-memory"),
            "Needs Node.js in the environment where the AI client runs",
            "https://github.com/modelcontextprotocol/servers", 0xFF64748B)
    ));

    private TerminowMcpCatalog() {}

    /** The entries of a category that match a search of the name, description, publisher and category. */
    @NonNull
    public static List<Entry> search(@NonNull String category, @NonNull String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> found = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (!CATEGORY_ALL.equals(category) && !entry.category.equals(category)) continue;
            if (!q.isEmpty() && !(entry.name.toLowerCase(Locale.ROOT).contains(q) ||
                entry.description.toLowerCase(Locale.ROOT).contains(q) ||
                entry.publisher.toLowerCase(Locale.ROOT).contains(q) ||
                entry.category.toLowerCase(Locale.ROOT).contains(q)))
                continue;
            found.add(entry);
        }
        return found;
    }

    @Nullable
    public static Entry find(@NonNull String id) {
        for (Entry entry : ENTRIES) if (entry.id.equals(id)) return entry;
        return null;
    }

}
