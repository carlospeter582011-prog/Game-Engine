/* Browser packaging for the Nexus Android WebView player. */
(() => {
  const utf8 = new TextEncoder();
  const decode = new TextDecoder();
  const utf16 = new TextDecoder('utf-16le');
  const bytes = (...values) => Uint8Array.of(...values);
  const join = (...parts) => {
    const out = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
    let offset = 0;
    for (const part of parts) { out.set(part, offset); offset += part.length; }
    return out;
  };
  const view = array => new DataView(array.buffer, array.byteOffset, array.byteLength);
  const int = n => { const out = new Uint8Array(4); view(out).setUint32(0,n,true); return out; };
  const long = n => join(int(n),int(0));
  const prefixed = data => join(int(data.length),data);
  const b64 = data => btoa(Array.from(new Uint8Array(data), c => String.fromCharCode(c)).join(''));
  const unbase64 = data => Uint8Array.from(atob(data), c => c.charCodeAt(0));
  const algorithm = {name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'};
  const signatureAlgorithm = bytes(0x30,0x0d,0x06,0x09,0x2a,0x86,0x48,0x86,0xf7,0x0d,0x01,0x01,0x0b,0x05,0x00);

  function der(tag,...parts) {
    const content = join(...parts);
    const n = content.length;
    const length = n < 128 ? bytes(n) : n < 256 ? bytes(0x81,n) : bytes(0x82,n >>> 8,n);
    return join(bytes(tag),length,content);
  }

  async function createKey() {
    const pair = await crypto.subtle.generateKey({...algorithm,modulusLength:2048,publicExponent:bytes(1,0,1)},true,['sign','verify']);
    const [privateKey, publicKey] = await Promise.all([
      crypto.subtle.exportKey('pkcs8',pair.privateKey),
      crypto.subtle.exportKey('spki',pair.publicKey)
    ]);
    const name = der(0x30,der(0x31,der(0x30,bytes(6,3,0x55,4,3),der(0x0c,utf8.encode('Nexus Game')))));
    const serial = crypto.getRandomValues(new Uint8Array(17));
    serial[0] = 1;
    const validity = der(0x30,der(0x18,utf8.encode('20200101000000Z')),der(0x18,utf8.encode('20991231235959Z')));
    const body = der(0x30,der(0xa0,bytes(2,1,2)),der(2,serial),signatureAlgorithm,name,validity,name,new Uint8Array(publicKey));
    const signature = new Uint8Array(await crypto.subtle.sign(algorithm,pair.privateKey,body));
    return {privateKey:b64(privateKey),publicKey:b64(publicKey),certificate:b64(der(0x30,body,signatureAlgorithm,der(3,bytes(0),signature)))};
  }

  async function backupKey(key,password,packageId) {
    const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12));
    const encryption = await passwordKey(password,salt);
    const payload = await crypto.subtle.encrypt({name:'AES-GCM',iv},encryption,utf8.encode(JSON.stringify({...key,packageId})));
    return JSON.stringify({format:'nexus-signing-key',version:1,salt:b64(salt),iv:b64(iv),payload:b64(payload)});
  }

  async function passwordKey(password,salt) {
    if (password.length < 8) throw Error('Use a backup password of at least 8 characters.');
    const base = await crypto.subtle.importKey('raw',utf8.encode(password),'PBKDF2',false,['deriveKey']);
    return crypto.subtle.deriveKey({name:'PBKDF2',hash:'SHA-256',salt,iterations:600000},base,{name:'AES-GCM',length:256},false,['encrypt','decrypt']);
  }

  async function restoreKey(content,password,packageId) {
    const file = JSON.parse(content);
    if (file.format !== 'nexus-signing-key' || file.version !== 1) throw Error('Choose a Nexus signing key backup.');
    const encryption = await passwordKey(password,unbase64(file.salt));
    try {
      const payload = await crypto.subtle.decrypt({name:'AES-GCM',iv:unbase64(file.iv)},encryption,unbase64(file.payload));
      const key = JSON.parse(decode.decode(payload));
      if (!key.privateKey || !key.publicKey || !key.certificate) throw Error('Missing signing key.');
      if (key.packageId !== packageId) throw Error("This backup belongs to another package ID.");
      return key;
    } catch { throw Error('Could not open the backup. Check the password and file.'); }
  }

  function patchManifest(original, options) {
    const source = original.slice(), d = view(source);
    const pool = d.getUint16(2,true), header = d.getUint16(pool+2,true);
    const oldSize = d.getUint32(pool+4,true), count = d.getUint32(pool+8,true);
    const flags = d.getUint32(pool+16,true), stringData = d.getUint32(pool+20,true);
    if (d.getUint16(pool,true) !== 1 || d.getUint32(pool+12,true) !== 0) throw Error('Invalid player string pool.');
    const isUtf8 = Boolean(flags & 0x100);
    const getLength8 = p => source[p] & 0x80 ? [(source[p]&127)*256+source[p+1],p+2] : [source[p],p+1];
    const names = Array.from({length:count},(_,i) => {
      const start = pool+stringData+d.getUint32(pool+header+i*4,true);
      if (isUtf8) {
        const [length, offset] = getLength8(getLength8(start)[1]);
        return decode.decode(source.subarray(offset,offset+length));
      }
      const first = d.getUint16(start,true);
      const large = first & 0x8000;
      const length = large ? (first&0x7fff)*65536+d.getUint16(start+2,true) : first;
      const offset = start + (large ? 4 : 2);
      return utf16.decode(source.subarray(offset,offset+length*2));
    });
    const replacements = new Map([
      ['com.nexusengine.templateplaceholder',options.packageId],
      ['NexusPlayerLabelPlaceholder',options.name],
      ['123456.123456.123456',options.version]
    ]);
    if (![...replacements.keys()].every(key => names.includes(key))) throw Error('Player metadata placeholders are missing.');
    const length8 = n => n < 128 ? bytes(n) : bytes(0x80|(n>>>8),n);
    const offsets = [];
    let total = 0;
    const strings = names.map(name => {
      const value = replacements.get(name) ?? name;
      const text = utf8.encode(value);
      let data;
      if (isUtf8) data = join(length8(value.length),length8(text.length),text,bytes(0));
      else {
        const littleEndian = new Uint8Array(value.length*2+4);
        const v = view(littleEndian);
        v.setUint16(0,value.length,true);
        for (let i=0;i<value.length;i++) v.setUint16(2+i*2,value.charCodeAt(i),true);
        data = littleEndian;
      }
      offsets.push(int(total)); total += data.length; return data;
    });
    let patched = 0;
    const end = pool+oldSize;
    for (let pos=end;pos+8<=source.length;) {
      const chunkSize = d.getUint32(pos+4,true);
      if (chunkSize < 8 || pos+chunkSize > source.length) throw Error('Invalid player manifest.');
      if (d.getUint16(pos,true) === 0x102) {
        const ext = pos+d.getUint16(pos+2,true);
        const element = names[d.getUint32(ext+4,true)];
        const first = ext+d.getUint16(ext+8,true), stride = d.getUint16(ext+10,true), fields = d.getUint16(ext+12,true);
        for (let i=0;i<fields;i++) {
          const at = first+i*stride;
          const name = names[d.getUint32(at+4,true)];
          if (element === 'manifest' && name === 'versionCode') { d.setInt32(at+16,options.build,true); patched++; }
          if (element === 'activity' && name === 'screenOrientation') { d.setInt32(at+16,options.orientation,true); patched++; }
        }
      }
      pos += chunkSize;
    }
    if (patched !== 2) throw Error('Player metadata fields are missing.');
    const padding = new Uint8Array((4-total%4)%4);
    const out = join(source.subarray(0,pool+header),...offsets,...strings,padding,source.subarray(end));
    const result = view(out);
    result.setUint32(4,out.length,true);
    result.setUint32(pool+4,out.length-source.length+oldSize,true);
    result.setUint32(pool+16,flags&~1,true);
    result.setUint32(pool+20,header+count*4,true);
    return out;
  }

  const crcTable = Uint32Array.from({length:256},(_,i) => {
    for (let bit=0;bit<8;bit++) i = i&1 ? 0xedb88320^(i>>>1) : i>>>1;
    return i;
  });

  function zip(files) {
    const local = [], directory = [];
    let offset = 0;
    for (const [name,data] of Object.entries(files)) {
      const encoded = utf8.encode(name);
      const padding = (4-(offset+30+encoded.length)%4)%4;
      const extra = padding ? bytes(0x35,0xd9,padding,0,...new Uint8Array(padding)) : bytes();
      let crc = 0xffffffff;
      for (const value of data) crc = crcTable[(crc^value)&255]^(crc>>>8);
      crc = (crc^0xffffffff)>>>0;
      const entry = new Uint8Array(30+encoded.length+extra.length), l = view(entry);
      l.setUint32(0,0x04034b50,true); l.setUint16(4,20,true);
      l.setUint16(12,33,true); l.setUint32(14,crc,true);
      l.setUint32(18,data.length,true); l.setUint32(22,data.length,true);
      l.setUint16(26,encoded.length,true); l.setUint16(28,extra.length,true);
      entry.set(encoded,30); entry.set(extra,30+encoded.length);
      const central = new Uint8Array(46+encoded.length), c = view(central);
      c.setUint32(0,0x02014b50,true); c.setUint16(4,20,true); c.setUint16(6,20,true);
      c.setUint16(14,33,true); c.setUint32(16,crc,true);
      c.setUint32(20,data.length,true); c.setUint32(24,data.length,true);
      c.setUint16(28,encoded.length,true); c.setUint32(42,offset,true);
      central.set(encoded,46);
      local.push(entry,data); directory.push(central); offset += entry.length+data.length;
    }
    const central = join(...directory);
    const end = new Uint8Array(22), e = view(end);
    e.setUint32(0,0x06054b50,true); e.setUint16(8,directory.length,true);
    e.setUint16(10,directory.length,true); e.setUint32(12,central.length,true); e.setUint32(16,offset,true);
    return [join(...local),central,end];
  }

  async function signApk(sections,key) {
    const chunks = [];
    const size = 1024*1024;
    for (const section of sections) {
      for (let at=0;at<section.length;at+=size) {
        const chunk = section.subarray(at,at+size);
        chunks.push(new Uint8Array(await crypto.subtle.digest('SHA-256',join(bytes(0xa5),int(chunk.length),chunk))));
      }
    }
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256',join(bytes(0x5a),int(chunks.length),...chunks)));
    const method = int(0x0103);
    const signedData = join(prefixed(prefixed(join(method,prefixed(digest)))),
      prefixed(prefixed(unbase64(key.certificate))),int(0));
    const privateKey = await crypto.subtle.importKey('pkcs8',unbase64(key.privateKey),algorithm,false,['sign']);
    const signature = new Uint8Array(await crypto.subtle.sign(algorithm,privateKey,signedData));
    const signer = join(prefixed(signedData),prefixed(prefixed(join(method,prefixed(signature)))),prefixed(unbase64(key.publicKey)));
    const value = join(int(0x7109871a),prefixed(prefixed(signer)));
    const pair = join(long(value.length),value);
    const length = pair.length+24;
    const block = join(long(length),pair,long(length),utf8.encode('APK Sig Block 42'));
    const [local,central,end] = sections;
    view(end).setUint32(16,local.length+block.length,true);
    return new Blob([local,block,central,end],{type:'application/vnd.android.package-archive'});
  }

  function validate(options) {
    const {name,packageId,version,build,orientation} = options;
    if (!name.trim() || name.length > 60 || /[\u0000-\u001f]/.test(name)) throw Error('Enter an app name of 1–60 characters.');
    if (packageId.length > 120 || !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(packageId) ||
        ['com.nexusengine.app','com.nexusengine.templateplaceholder'].includes(packageId)) throw Error('Enter a package ID like com.example.mygame.');
    if (!/^\d{1,6}(\.\d{1,6}){2}$/.test(version)) throw Error('Enter a version like 1.0.0.');
    if (!Number.isInteger(build) || build < 1 || build > 999999999) throw Error('Enter a build number from 1 to 999999999.');
    if (![-1,6,7].includes(orientation)) throw Error('Choose a screen orientation.');
  }

  async function build(template,html,options,key) {
    validate(options);
    const game = utf8.encode(html);
    if (game.length > 20*1024*1024 || !/^\s*<!doctype html>/i.test(html)) throw Error('Use a standalone HTML game up to 20 MB.');
    const files = {};
    for (const name of ['AndroidManifest.xml','resources.arsc','classes.dex']) files[name] = unbase64(template[name]);
    const iconName = Object.keys(template).find(name => /^res\/drawable[^/]*\/game_icon\.png$/.test(name));
    if (!iconName) throw Error('The Android player icon is missing.');
    files[iconName] = options.icon || unbase64(template[iconName]);
    files['AndroidManifest.xml'] = patchManifest(files['AndroidManifest.xml'],options);
    files['assets/game.html'] = game;
    return signApk(zip(files),key);
  }

  globalThis.NexusApk = {build,createKey,backupKey,restoreKey,validate};
})();
