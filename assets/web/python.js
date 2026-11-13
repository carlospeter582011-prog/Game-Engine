function installNexusPython(env) {
  const sk = env.Sk;
  const allowed = new Set(['src/lib/math.js', 'src/lib/random.js']);
  let write = () => {};
  const configure = () => {
    if (!sk) throw Error('Python runtime could not be loaded.');
    sk.configure({
      __future__: sk.python3,
      execLimit: 25,
      output: value => write(value.trimEnd()),
      read: name => {
        if (allowed.has(name) && sk.builtinFiles.files[name]) {
          return sk.builtinFiles.files[name];
        }
        throw Error('Module unavailable: ' + name);
      }
    });
  };
  return {
    validate(source) {
      configure();
      sk.compile(source, 'behavior.py', 'exec', false);
    },
    create(source, log) {
      configure();
      write = log;
      const globals = sk.importMainWithBody('behavior', false, source, false).$d;
      const update = globals.on_event;
      if (!update || !sk.builtin.checkCallable(update)) {
        throw Error('Define on_event(event, dt, game).');
      }
      return (event, dt, game) => {
        write = log;
        sk.execStart = Date.now();
        const context = sk.ffi.remapToPy(game);
        sk.misceval.callsimArray(update, [new sk.builtin.str(event), new sk.builtin.float_(dt), context]);
        const number = key => {
          const value = context.mp$lookup(new sk.builtin.str(key));
          return value instanceof sk.builtin.int_ || value instanceof sk.builtin.float_
            ? Number(value.v) : NaN;
        };
        return {x: number('x'), y: number('y'), vx: number('vx'), vy: number('vy')};
      };
    }
  };
}
