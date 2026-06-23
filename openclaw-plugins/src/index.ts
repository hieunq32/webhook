import { Type } from "typebox";
import { defineToolPlugin } from "openclaw/plugin-sdk/tool-plugin";

export default defineToolPlugin({
  id: "facebook-group-rpa",
  name: "Facebook Group RPA",
  description: "Post recruitment content to Facebook Groups through the local Playwright RPA service.",
  configSchema: Type.Object({
    rpaToolsInvokeUrl: Type.Optional(Type.String({
      description: "Local Facebook Group RPA /tools/invoke endpoint.",
      default: "http://127.0.0.1:18990/tools/invoke",
    })),
    rpaAuthToken: Type.Optional(Type.String({
      description: "Optional bearer token for the local Facebook Group RPA service.",
      default: "",
    })),
  }, { additionalProperties: false }),
  tools: (tool) => [
    tool({
      name: "facebookGroupPost",
      description: "Submit one generated recruitment post to a configured Facebook Group using browser RPA.",
      parameters: Type.Object({
        jobDescriptionId: Type.Number({ description: "Internal JobDescription id." }),
        groupId: Type.Number({ description: "Internal Facebook group target id." }),
        groupName: Type.String({ description: "Human-readable group name." }),
        groupReference: Type.String({ description: "Facebook group URL or group id." }),
        content: Type.String({ description: "Final post content ready to publish." }),
        scriptName: Type.Optional(Type.String({ description: "Workflow/script name for compatibility." })),
      }),
      execute: async (params, config = {}) => {
        const endpoint = config.rpaToolsInvokeUrl || "http://127.0.0.1:18990/tools/invoke";
        const headers: Record<string, string> = {
          "content-type": "application/json",
        };
        if (config.rpaAuthToken) {
          headers.authorization = `Bearer ${config.rpaAuthToken}`;
        }

        const response = await fetch(endpoint, {
          method: "POST",
          headers,
          body: JSON.stringify({
            tool: "facebookGroupPost",
            action: "post",
            sessionKey: `openclaw-facebook-group-post:${params.jobDescriptionId}:${params.groupId}`,
            args: params,
          }),
        });

        const text = await response.text();
        let body: unknown;
        try {
          body = text ? JSON.parse(text) : {};
        } catch {
          body = { raw: text };
        }

        if (!response.ok) {
          return {
            ok: false,
            success: false,
            error: `Local Facebook Group RPA returned HTTP ${response.status}`,
            result: body,
          };
        }

        return body;
      },
    }),
  ],
});
