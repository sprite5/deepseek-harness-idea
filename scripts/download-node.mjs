// 下载 Node.js 发行包（build-runtime.ps1 复用；Node 内置 fetch，避免系统栈网络问题）
import fs from 'fs';
const url = process.argv[2];
const out = process.argv[3];
fetch(url).then(async (r) => {
  if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + url);
  const buf = Buffer.from(await r.arrayBuffer());
  fs.writeFileSync(out, buf);
  console.log('downloaded ' + buf.length + ' bytes -> ' + out);
}).catch((e) => { console.error(e); process.exit(1); });
