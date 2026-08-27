import crypto from 'node:crypto';
import fs from 'node:fs';

const file = process.argv[2];
if (!file) {
  process.exit(1);
}
const hash = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
console.log(hash);
