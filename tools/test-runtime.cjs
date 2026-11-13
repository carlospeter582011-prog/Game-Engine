const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const base=require('node:path').join(__dirname,'../assets/web/');
const data=new Map();
const c=vm.createContext({console,setTimeout,clearTimeout,TextEncoder,TextDecoder,performance,
  localStorage:{getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v)}});
c.window=c;c.self=c;c.addEventListener=()=>{};
c.document={createElement:()=>({getContext:()=>new Proxy({}, {get:()=>()=>{}}),toDataURL:()=>''})};
for(const f of ['vendor/skulpt.min.js','vendor/skulpt-stdlib.js','python.js']){
 vm.runInContext(fs.readFileSync(base+f,'utf8'),c,{filename:f});
}
const file=fs.readFileSync(base+'editor.html','utf8');
const code=file.split('<script>\n').slice(1).join('<script>\n');
const script=code.split('const App =')[0];
new vm.Script(code.slice(0,code.lastIndexOf('</script>')));
vm.runInContext(script,c,{filename:'editor.js'});
const result=vm.runInContext(`
const project=Templates.find(template=>template.name==='Checkpoint Campaign').build();
const output=[];
const rt=new (createNexusRuntime(window).GameRuntime)(project,null,{onLog:(message,level)=>{if(level==='err')output.push(message)}});
rt.init();
const flow=[];
const button=name=>{
 for(const entity of [...rt.entities]) rt.triggerEntityEvent(entity,'OnButtonPress',{eventName:name});
 rt.step(0);
};
const complete=()=>{
 const goal=rt.entityMap.get('e_goal');
 rt.triggerEntityEvent(goal,'OnTriggerEnter',{other:rt.entityMap.get('e_plr')});
 rt.step(0);
 flow.push(rt.scene.name);
};
flow.push(rt.scene.name);
button('continue');
flow.push(rt.entityMap.get('title').components.UIElement.text);
button('start');
flow.push(rt.scene.name,rt.project.variables.level);
button('checkpoint');
flow.push(rt.entityMap.get('save_button').components.UIElement.text);
complete(); complete(); complete();
flow.push(rt.project.variables.gameResult);
button('retry'); button('continue');
flow.push(rt.scene.name,rt.project.variables.level);
const player=rt.entityMap.get('e_plr');
player.components.ScriptBehavior.pythonSource += '\\n    if event == "OnMessage":\\n        game["set_var"]("received", game["event_value"])\\n';
player.components.ScriptBehavior._pythonEvent=null;
rt.broadcast('test',17,player); rt.step(0);
flow.push(rt.project.variables.received);
JSON.stringify({flow,output})
`,c);
assert.deepEqual(JSON.parse(result),{flow:['Main Menu','No checkpoint yet','Level 1',1,'SAVED','Level 2','Level 3','Finish','win','Level 1',1,17],output:[]});
const resize=file.slice(file.indexOf('  function resizeTilemap('),file.indexOf('  function openTilemapSettings('));
const tile=vm.runInContext(resize+'; const tile={cols:2,rows:2,tiles:[1,2,3,4]};resizeTilemap(tile,3,3,16);JSON.stringify(tile)',c);
assert.deepEqual(JSON.parse(tile),{cols:3,rows:3,tileSize:16,tiles:[1,2,0,3,4,0,0,0,0]});
console.log('Campaign start/end, save/load, Python messages, and tilemap resize passed.');

const landing = vm.runInContext(`
  (() => {
    const demo = new NexusRuntime.GameRuntime(Templates[0].build(), null);
    demo.init();
    for (let frame = 0; frame < 600; frame++) demo.step(1 / 60);
    const player = demo.entityMap.get('e_plr');
    return player._grounded && player.components.Transform.y === 529;
  })()
`, c);
assert.equal(landing, true, 'Player should stay on the floor across a tile seam');

const scripting = vm.runInContext(`
  (() => {
    const project = Templates[0].build();
    project.dataTables = {items:{potion:{price:10}}};
    project.inputActions = {Jump:{key:'Space'}};
    const runtime = new NexusRuntime.GameRuntime(project,null);
    runtime.init();
    const player = runtime.entityMap.get('e_plr');
    const map = runtime.entities.find(entity => entity.components.Tilemap);
    const commands = runtime.gameCommands(player,{});
    const behavior = player.components.ScriptBehavior ||= {enabled:true};
    behavior.pythonSource = 'def on_event(event, dt, game):\\n    if event == "OnMessage":\\n        game["write_property"]("self", "Transform", "x", 67)\\n        game["set_var"]("price", game["data_value"]("items", "potion", "price"))';
    runtime.triggerEntityEvent(player,'OnMessage');
    const pythonResult = [player.components.Transform.x,runtime.project.variables.price];
    const stored = commands.write_tile(map.id,0,0,4);
    const saved = commands.save_world('test');
    commands.write_tile(map.id,0,0,6);
    const loaded = commands.load_world('test');
    runtime.step(0);
    runtime.keys.add('Space');
    runtime.refreshActions();
    const pressed = commands.action_held('Jump');
    runtime.keys.delete('Space');
    runtime.refreshActions();
    return JSON.stringify({pythonResult,stored,saved,loaded,tile:commands.read_tile(map.id,0,0),
      invalidTile:commands.write_tile(map.id,-1,0,5),pressed,released:!commands.action_held('Jump'),
      turn:commands.advance_turn(2),stable:commands.seeded_random(45)===commands.seeded_random(45)});
  })()
`,c);
assert.deepEqual(JSON.parse(scripting),{pythonResult:[67,10],stored:true,saved:true,loaded:true,tile:4,
  invalidTile:false,pressed:true,released:true,turn:1,stable:true});
console.log('Shared Python commands, tile persistence, input actions, turns, and seeded randomness passed.');
