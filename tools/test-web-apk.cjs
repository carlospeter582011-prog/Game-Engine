const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const {X509Certificate, createPublicKey, verify, createHash} = require('node:crypto');
require('../assets/web/apk.js');
const template = require('../assets/web/player-template.json');

function inspect(apk) {
  const end = Buffer.from(apk.subarray(-22));
  const central = end.readUInt32LE(16);
  assert.equal(apk.subarray(central-16,central).toString(),'APK Sig Block 42');
  const block = central-8-Number(apk.readBigUInt64LE(central-24));
  assert.equal(apk.readUInt32LE(block+16),0x7109871a);
  assert.equal(apk.readBigUInt64LE(block),apk.readBigUInt64LE(central-24));
  const reader = data => () => {
    const length = data.readUInt32LE();
    assert(length <= data.length-4);
    const value = data.subarray(4,4+length);
    data = data.subarray(4+length);
    return value;
  };
  const signers = reader(apk.subarray(block+20,central-24))();
  const signer = reader(reader(signers)());
  const signed = signer(), signatures = reader(signer())(), pubkey = signer();
  assert.equal(signatures.readUInt32LE(),0x0103);
  const key = createPublicKey({key:pubkey,format:'der',type:'spki'});
  assert(verify('sha256',signed,key,reader(signatures.subarray(4))()));
  const fields = reader(signed);
  const digest = reader(fields())();
  const cert = new X509Certificate(reader(fields())());
  assert(cert.verify(key));
  end.writeUInt32LE(block,16);
  const hashes = [];
  for (const section of [apk.subarray(0,block),apk.subarray(central,-22),end]) {
    for (let i=0;i<section.length;i+=1048576) {
      const chunk = section.subarray(i,i+1048576);
      const header = Buffer.alloc(5,0xa5);
      header.writeUInt32LE(chunk.length,1);
      hashes.push(createHash('sha256').update(header).update(chunk).digest());
    }
  }
  const header = Buffer.alloc(5,0x5a);
  header.writeUInt32LE(hashes.length,1);
  assert.equal(digest.readUInt32LE(),0x0103);
  assert.deepEqual(reader(digest.subarray(4))(),createHash('sha256').update(header).update(Buffer.concat(hashes)).digest());
  return cert.fingerprint256;
}

async function main() {
  const options = {name:'Nexus لعبة 🎮',packageId:'com.carlos.nexus.offlinegame',version:'2.3.4',build:42,orientation:7};
  if (process.argv[3]) options.icon = await fs.readFile(process.argv[3]);
  const html = await fs.readFile(require('node:path').join(__dirname,'smoke-game.html'),'utf8');
  const key = await NexusApk.createKey();
  const original = Buffer.from(await (await NexusApk.build(template,html,options,key)).arrayBuffer());
  const fingerprint = inspect(original);
  const backup = await NexusApk.backupKey(key,'test-password',options.packageId);
  await assert.rejects(NexusApk.restoreKey(backup,'wrong-password',options.packageId));
  const restored = await NexusApk.restoreKey(backup,'test-password',options.packageId);
  const update = Buffer.from(await (await NexusApk.build(template,html,{...options,build:43},restored)).arrayBuffer());
  assert.equal(inspect(update),fingerprint);
  if (process.argv[2]) await fs.writeFile(process.argv[2],original);
  console.log('Web APK signature, content digest, certificate and signing key reuse passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
