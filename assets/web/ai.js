/* Provider selection and structured generation for the Nexus editor. */
(() => {
  const instructions = {
    game:'Return {name,width,height,background,gravity,variables,objects,tilemap,sprites,sounds}. Include a playable objective, controls, score or feedback, a win or loss condition, and a Restart button. Use gameplay blocks attached to objects.',
    tilemap:'Return {tilemap:{name,cols,rows,tileSize,tiles,collisionIds}}. Tiles is a flat row-major integer array. IDs 0 empty, 1 grass, 2 stone, 3 crate, 4 hazard, 5 floor, 6 neon, 7 dirt.',
    sprite:'Return {sprites:[{name,palette:{A:"#ffffff"},rows:[".AA.","AAAA"]}]}. Use a compact square pixel-art grid (up to 48×48). Period is transparent.',
    sound:'Return {sounds:[{name,wave:"square",freq:440,endFreq:220,dur:0.18,vol:0.4}]}. Waves: sine, square, sawtooth, triangle.',
    logic:'Return {objects:[{name,stacks:[{event:{type:"OnStart",params:{}},actions:[{type:"SetVariable",params:{key:"score",value:0}}]}]}]}. Use an exact existing object name.'
  };

  function endpoint(value) {
    const url = new URL(value.trim());
    if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.search)
      throw Error('Use an HTTPS API base URL without credentials or query parameters.');
    url.pathname = url.pathname.replace(/\/+$/,'');
    if (!url.pathname.endsWith('/chat/completions')) url.pathname += '/chat/completions';
    return url.href;
  }

  function parse(content) {
    if (typeof content !== 'string' || content.length > 200000) throw Error('The provider did not return a usable text response.');
    const value = JSON.parse(content.trim().replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,''),
      (key,item) => {
        if (['__proto__','prototype','constructor'].includes(key)) throw Error('The plan contains a reserved key.');
        return item;
      });
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('The AI plan must be a JSON object.');
    return value;
  }

  async function generate(profiles,mode,prompt,catalog,objects,signal) {
    if (!instructions[mode]) throw Error('Choose a generation mode.');
    const tasks = [
      'Return one JSON object only. Create data for the Nexus 2D engine. Never return HTML, JavaScript, Python, links, or code.',
      instructions[mode],
      'Game object shape: {name,x,y,components:{ShapeRenderer:{shape:"rect",width:40,height:40,color:"#88aaff"},Collider:{isTrigger:true},RigidBody:{bodyType:"static"},CharacterController:{mode:"platformer"},UIElement:{uiType:"button",text:"Restart",anchor:"top-right",buttonAction:"restart"}},tags:["player"],stacks:[{event:{type:"OnStart",params:{}},actions:[{type:"SetVariable",params:{key:"score",value:0}}]}]}. Use a static floor for platformers. For sprites set SpriteRenderer.assetId to the sprite name. For audio blocks set preset to the sound name. Use a Jump UI button with eventName "jump" for touchscreen.',
      'For a tilemap use the tilemap schema: {name,cols,rows,tileSize,tiles,collisionIds}. Sprite schema: {name,palette,rows}. Sound schema: {name,wave,freq,endFreq,dur,vol}. Each sound must be short.',
      'Action blocks run in listed order using their out port. Use terminal actions only at the end. Use an event for each stack. Use only these block names and parameters: '+JSON.stringify(catalog),
      'Scene object names: '+JSON.stringify(objects)
    ].join('\n');
    let last;
    for (const profile of profiles) {
      for (const key of profile.keys) {
        if (signal.aborted) throw signal.reason;
        try {
          const response = await fetch(endpoint(profile.url),{
            method:'POST',signal,redirect:'error',credentials:'omit',referrerPolicy:'no-referrer',
            headers:{'Content-Type':'application/json',Authorization:'Bearer '+key},
            body:JSON.stringify({model:profile.model,messages:[{role:'system',content:tasks},{role:'user',content:prompt}]})
          });
          if (!response.ok) {
            last = 'Provider returned HTTP '+response.status+'.';
            if ([400,401,403,404,429].includes(response.status) || response.status >= 500) continue;
            throw Error(last);
          }
          const value = await response.json();
          return parse(value.choices?.[0]?.message?.content);
        } catch (error) {
          if (signal.aborted) throw signal.reason;
          last = error instanceof SyntaxError ? 'The provider returned invalid JSON.' :
            error instanceof TypeError ? 'The endpoint could not be reached. Check its URL and browser CORS support.' :
            'The provider did not return a supported game plan.';
        }
      }
    }
    throw Error(last || 'Add an API URL, model and at least one key.');
  }

  globalThis.NexusAi = {endpoint,generate};
})();
