// 计算文件的 SHA-256（输出大写 hex，与 build-runtime.ps1 的 $NodeSha256 默认值大小写一致）。
// 用法: node sha256.mjs <file>
// 说明: build-runtime.ps1 在受限环境中 Get-FileHash(.NET) 可能不可用，改用 Node crypto 校验。
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const file = process.argv[2];
if (!file) {
  console.error('usage: node sha256.mjs <file>');
  process.exit(2);
}
const hash = createHash('sha256');
hash.update(readFileSync(file));
console.log(hash.digest('hex').toUpperCase());
