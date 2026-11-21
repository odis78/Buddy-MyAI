import test from "node:test";
import assert from "node:assert/strict";
import { parseRpcResponse } from "./sse-response.mjs";

test("parses plain JSON-RPC response", () => {
  assert.equal(parseRpcResponse(JSON.stringify({jsonrpc:"2.0",id:1,result:{ok:true}}),1).result.ok,true);
});
test("parses a single SSE response event", () => {
  assert.equal(parseRpcResponse('data: {"jsonrpc":"2.0","id":1,"result":{"ok":true}}\n\n',1).result.ok,true);
});
test("skips notification before response", () => {
  const s='event: message\ndata: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}\n\nevent: message\ndata: {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}\n\n';
  assert.deepEqual(parseRpcResponse(s,1).result,{tools:[]});
});
test("skips unrelated response ids", () => {
  const s='data: {"jsonrpc":"2.0","id":2,"result":{"wrong":true}}\n\ndata: {"jsonrpc":"2.0","id":1,"result":{"right":true}}\n\n';
  assert.equal(parseRpcResponse(s,1).result.right,true);
});
test("supports CRLF event separators", () => {
  assert.deepEqual(parseRpcResponse('data: {"jsonrpc":"2.0","id":1,"result":{}}\r\n\r\n',1).result,{});
});
test("joins multiline data fields", () => {
  const s='data: {"jsonrpc":"2.0",\ndata: "id":1,"result":{"ok":true}}\n\n';
  assert.equal(parseRpcResponse(s,1).result.ok,true);
});
test("ignores comments and data-less events", () => {
  const s=': heartbeat\n\nevent: ping\n\ndata: {"jsonrpc":"2.0","id":1,"result":7}\n\n';
  assert.equal(parseRpcResponse(s,1).result,7);
});
test("preserves JSON-RPC errors for caller", () => {
  const s='data: {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"denied"}}\n\n';
  assert.equal(parseRpcResponse(s,1).error.code,-32000);
});
test("rejects empty body", () => assert.throws(() => parseRpcResponse("  ",1),/empty response/));
test("rejects stream without matching response", () => assert.throws(() => parseRpcResponse('data: {"jsonrpc":"2.0","method":"notifications/initialized"}\n\n',1),/without the matching/));
test("rejects wrong id in JSON response", () => assert.throws(() => parseRpcResponse('{"jsonrpc":"2.0","id":9,"result":{}}',1),/unexpected id/));
test("rejects malformed SSE JSON", () => assert.throws(() => parseRpcResponse("data: {broken}\n\n",1),/invalid JSON/));
