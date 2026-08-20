#!/usr/bin/env node
/**
 * mcp-ide-server.mjs — DSH ↔ IDE Bridge 的 MCP server（streamable-http，无状态）。
 *
 * 由插件在运行期写入 DSH_HOME（放在 DSH_HOME 下以便 node 向上查找命中
 * profiles/node_modules 中的 @modelcontextprotocol/sdk 与 express）。
 *
 * 环境变量：
 *   DSH_IDE_BRIDGE_URL   IDE Bridge 地址，如 http://127.0.0.1:38123（必填）
 *   DSH_IDE_TOKEN        Bridge 鉴权 token（必填）
 *   DSH_MCP_PORT         MCP 监听端口（默认 0 = OS 随机分配）
 *   DSH_MCP_HOST         监听地址（默认 127.0.0.1）
 *
 * 工具（模型侧名 mcp__ide__<raw>，见 docs/DESIGN.md §4.2）：
 *   ide_get_selection / ide_get_open_files / ide_get_project_tree / ide_get_sent_selection
 *   / ide_open_file / ide_reveal_file
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { createMcpExpressApp } from '@modelcontextprotocol/sdk/server/express.js';
import * as z from 'zod/v4';

const BRIDGE_URL = process.env.DSH_IDE_BRIDGE_URL;
const TOKEN = process.env.DSH_IDE_TOKEN;
const HOST = process.env.DSH_MCP_HOST || '127.0.0.1';
const PORT = Number(process.env.DSH_MCP_PORT || '0');

if (!BRIDGE_URL || !TOKEN) {
  console.error('mcp-ide-server: DSH_IDE_BRIDGE_URL and DSH_IDE_TOKEN are required');
  process.exit(1);
}

/** 调用 IDE Bridge；结构化错误，不抛未捕获异常。 */
async function bridge(method, path, body) {
  try {
    const res = await fetch(BRIDGE_URL + path, {
      method,
      headers: {
        'X-DSH-IDE-Token': TOKEN,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 401) {
      return { isError: true, content: [{ type: 'text', text: JSON.stringify({ error: 'ide bridge auth failed', code: 'unauthorized' }) }] };
    }
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      return { isError: true, content: [{ type: 'text', text: JSON.stringify(data ?? { error: 'ide bridge error', code: String(res.status) }) }] };
    }
    return { content: [{ type: 'text', text: JSON.stringify(data) }] };
  } catch (e) {
    return { isError: true, content: [{ type: 'text', text: JSON.stringify({ error: 'ide bridge unreachable', detail: String(e.message ?? e) }) }] };
  }
}

const server = new McpServer(
  { name: 'dsh-ide-bridge', version: '0.1.0' },
  { capabilities: {} },
);

server.registerTool('ide_get_selection', {
  description: '获取当前 IntelliJ 编辑器中的选中代码（含文件路径、语言、行号）',
  inputSchema: {},
}, async () => bridge('GET', '/selection'));

server.registerTool('ide_get_open_files', {
  description: '列出 IDE 当前打开的文件（路径、语言、是否已修改）',
  inputSchema: {},
}, async () => bridge('GET', '/open-files'));

server.registerTool('ide_get_project_tree', {
  description: '获取当前项目目录树（忽略构建产物等噪音目录）',
  inputSchema: {
    depth: z.number().int().min(1).max(10).describe('遍历深度，默认 4').optional(),
  },
}, async ({ depth }) => bridge('GET', '/project-tree' + (depth ? `?depth=${depth}` : '')));

server.registerTool('ide_get_sent_selection', {
  description: '获取用户最近一次通过 IDE 动作推送的选中代码（即使注入失败也不丢上下文）',
  inputSchema: {},
}, async () => bridge('GET', '/sent-selection?latest=1'));

server.registerTool('ide_open_file', {
  description: '在 IntelliJ 中打开指定文件',
  inputSchema: {
    path: z.string().describe('文件绝对路径'),
  },
}, async ({ path }) => bridge('POST', '/open-file', { path }));

server.registerTool('ide_reveal_file', {
  description: '在 IntelliJ 项目树中定位指定文件',
  inputSchema: {
    path: z.string().describe('文件绝对路径'),
  },
}, async ({ path }) => bridge('POST', '/reveal', { path }));

const app = createMcpExpressApp();

function makeTransport() {
  return new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
}

app.post('/mcp', async (req, res) => {
  const transport = makeTransport();
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error('mcp request error:', error);
    if (!res.headersSent) {
      res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: 'Internal server error' }, id: null });
    }
  } finally {
    res.on('close', () => {
      transport.close().catch(() => {});
      server.close().catch(() => {});
    });
  }
});

// streamable-http 规范：GET 用于会话协商（无状态模式返回 405），DELETE 不支持
app.get('/mcp', (_req, res) => {
  res.writeHead(405).end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32000, message: 'Method not allowed.' }, id: null }));
});
app.delete('/mcp', (_req, res) => {
  res.writeHead(405).end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32000, message: 'Method not allowed.' }, id: null }));
});

const httpServer = app.listen(PORT, HOST, () => {
  const addr = httpServer.address();
  const port = typeof addr === 'object' && addr ? addr.port : PORT;
  console.log(`mcp-ide-server: listening on http://${HOST}:${port}/mcp`);
  console.log(`mcp-ide-server: bridge=${BRIDGE_URL}`);
});

process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
