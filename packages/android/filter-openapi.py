#!/usr/bin/env python3
"""Filter the OpenCode OpenAPI spec to only include paths and schemas needed by the Android app."""

import json
import sys

INPUT = "../../packages/sdk/openapi.json"
OUTPUT = "api-gen/src/main/resources/opencode-filtered.json"

# Paths to include (V1 instance API only, no /api/* V2, no experimental, no tui, no pty, no sync)
INCLUDE_PATTERNS = [
    "/global/health",
    "/global/event",
    "/global/config",
    "/global/dispose",
    "/global/upgrade",
    "/project",
    "/project/current",
    "/project/{projectID}",
    "/project/{projectID}/directories",
    "/project/git/init",
    "/session",
    "/session/status",
    "/session/{sessionID}",
    "/session/{sessionID}/message",
    "/session/{sessionID}/message/{messageID}",
    "/session/{sessionID}/message/{messageID}/part/{partID}",
    "/session/{sessionID}/prompt_async",
    "/session/{sessionID}/abort",
    "/session/{sessionID}/fork",
    "/session/{sessionID}/share",
    "/session/{sessionID}/todo",
    "/session/{sessionID}/summarize",
    "/session/{sessionID}/revert",
    "/session/{sessionID}/unrevert",
    "/session/{sessionID}/children",
    "/session/{sessionID}/diff",
    "/session/{sessionID}/init",
    "/session/{sessionID}/command",
    "/session/{sessionID}/permissions/{permissionID}",
    "/config",
    "/config/providers",
    "/provider",
    "/provider/auth",
    "/provider/{providerID}/oauth/authorize",
    "/provider/{providerID}/oauth/callback",
    "/agent",
    "/command",
    "/skill",
    "/permission",
    "/permission/{requestID}/reply",
    "/question",
    "/question/{requestID}/reply",
    "/question/{requestID}/reject",
    "/path",
    "/vcs",
    "/vcs/status",
    "/vcs/diff",
    "/vcs/diff/raw",
    "/vcs/apply",
    "/event",
    "/auth/{providerID}",
    "/log",
    "/instance/dispose",
    "/file",
    "/file/content",
    "/file/status",
    "/find",
    "/find/file",
    "/find/symbol",
    "/lsp",
    "/formatter",
    "/mcp",
    "/mcp/{name}/connect",
    "/mcp/{name}/disconnect",
]


def collect_refs(schema, refs):
    """Recursively collect all $ref URLs from a schema object."""
    if isinstance(schema, dict):
        if "$ref" in schema:
            ref = schema["$ref"]
            if ref.startswith("#/components/schemas/"):
                name = ref.split("/")[-1]
                refs.add(name)
        for v in schema.values():
            collect_refs(v, refs)
    elif isinstance(schema, list):
        for item in schema:
            collect_refs(item, refs)


def main():
    with open(INPUT) as f:
        spec = json.load(f)

    # Filter paths
    filtered_paths = {}
    for pattern in INCLUDE_PATTERNS:
        # Try both /path and //path (spec has // prefix)
        for key in [pattern, "/" + pattern, "//" + pattern.lstrip("/")]:
            if key in spec["paths"]:
                filtered_paths[key] = spec["paths"][key]
                break

    # Collect all referenced schemas
    refs = set()
    for path_item in filtered_paths.values():
        collect_refs(path_item, refs)

    # Recursively expand refs (schemas may reference other schemas)
    all_schemas = spec.get("components", {}).get("schemas", {})
    expanded = set()
    to_check = list(refs)
    while to_check:
        name = to_check.pop()
        if name in expanded or name not in all_schemas:
            continue
        expanded.add(name)
        collect_refs(all_schemas[name], refs)
        for new_ref in refs - expanded:
            if new_ref not in to_check:
                to_check.append(new_ref)

    # Filter schemas
    filtered_schemas = {name: all_schemas[name] for name in expanded if name in all_schemas}

    # Build filtered spec
    filtered = {
        "openapi": spec["openapi"],
        "info": spec["info"],
        "paths": filtered_paths,
        "components": {
            "schemas": filtered_schemas,
            **{k: v for k, v in spec.get("components", {}).items() if k != "schemas"}
        },
    }

    # Copy security schemes if present
    if "security" in spec:
        filtered["security"] = spec["security"]

    with open(OUTPUT, "w") as f:
        json.dump(filtered, f, indent=2)

    print(f"Filtered: {len(filtered_paths)} paths, {len(filtered_schemas)} schemas")
    print(f"Output: {OUTPUT}")


if __name__ == "__main__":
    main()
