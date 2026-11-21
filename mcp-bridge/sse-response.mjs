// Parse Buddy's JSON or Server-Sent Events JSON-RPC response.
// MCP servers may send notifications before the response on the same SSE stream.
export function parseRpcResponse(body, expectedId) {
  if (typeof body !== "string" || !body.trim()) {
    throw new Error("Buddy returned an empty response");
  }

  const trimmed = body.trim();
  if (trimmed.startsWith("{")) {
    const message = JSON.parse(trimmed);
    if (!message || typeof message !== "object" || Array.isArray(message)) {
      throw new Error("Buddy returned an invalid JSON-RPC response");
    }
    if (message.jsonrpc !== "2.0") {
      throw new Error("Buddy returned a response without JSON-RPC 2.0 version");
    }
    if (message.id !== expectedId) {
      throw new Error("Buddy returned a JSON-RPC response with an unexpected id");
    }
    const hasResult = Object.prototype.hasOwnProperty.call(message, "result");
    const hasError = Object.prototype.hasOwnProperty.call(message, "error");
    if (hasResult === hasError) {
      throw new Error("Buddy returned a JSON-RPC response without exactly one of result or error");
    }
    if (hasError && (!message.error || typeof message.error !== "object" || !Number.isInteger(message.error.code) || typeof message.error.message !== "string")) {
      throw new Error("Buddy returned an invalid JSON-RPC error object");
    }
    return message;
  }

  const normalized = body.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  const events = normalized.split(/\n\n+/);
  for (const event of events) {
    const data = [];
    for (const line of event.split("\n")) {
      if (!line || line.startsWith(":")) continue;
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "data") data.push(value);
    }
    if (!data.length) continue;

    const payload = data.join("\n");
    let message;
    try {
      message = JSON.parse(payload);
    } catch (error) {
      throw new Error("Buddy returned invalid JSON in an SSE data event: " + error.message);
    }
    if (!message || typeof message !== "object" || Array.isArray(message)) {
      throw new Error("Buddy returned an invalid JSON-RPC SSE event");
    }
    if (message.jsonrpc !== "2.0") {
      throw new Error("Buddy returned an SSE message without JSON-RPC 2.0 version");
    }
    // Notifications (including tools/list_changed) have no id. Ignore them.
    if (!Object.prototype.hasOwnProperty.call(message, "id")) continue;
    if (message.id !== expectedId) continue;
    const hasResult = Object.prototype.hasOwnProperty.call(message, "result");
    const hasError = Object.prototype.hasOwnProperty.call(message, "error");
    if (hasResult === hasError) {
      throw new Error("Buddy returned a JSON-RPC response without exactly one of result or error");
    }
    if (hasError && (!message.error || typeof message.error !== "object" || !Number.isInteger(message.error.code) || typeof message.error.message !== "string")) {
      throw new Error("Buddy returned an invalid JSON-RPC error object");
    }
    return message;
  }
  throw new Error("Buddy's SSE stream ended without the matching JSON-RPC response");
}
