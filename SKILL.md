---
name: oracle-vps-terminal
description: Direct admin access to the user's Oracle VPS to execute bash commands, read logs, and manage files without manual curl.
---

# Oracle VPS OpenTerminal Sandbox

This skill gives you (the AI) native and direct access to the user's Oracle VPS. 
You do not need to ask the user to type `curl` commands. Whenever the user asks you to check system status, run scripts, or manage the server, you MUST execute the commands yourself using the OpenAPI server.

## Connection Details
To understand the available commands and how to execute them, fetch and read the schema from:
**URL:** `http://80.225.245.81:8081/openapi.json`

## Authentication (CRITICAL)
Every API request you make to this server MUST include the following authentication header:
`Authorization: Bearer rip123`

## Instructions for AI
1. Analyze the user's request (e.g., "Check disk space").
2. Call the endpoint defined in the `openapi.json` to execute the corresponding terminal command (e.g., `df -h`).
3. Read the output from the API response.
4. Present the final, easy-to-read result to the user. Do not show the raw JSON unless asked.
