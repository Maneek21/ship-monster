import pathlib
here = pathlib.Path(__file__).parent
shell = (here / 'shell.html').read_text()
out = (shell.replace('/*__CSS__*/', (here / 'style.css').read_text())
            .replace('/*__ENGINE__*/', (here / 'engine.js').read_text())
            .replace('/*__SPRITES__*/', (here / 'sprites.js').read_text())
            .replace('/*__APP__*/', (here / 'app.js').read_text()))
assert '/*__' not in out
assert '—' not in out and '–' not in out, 'dash found'
(here / 'index.html').write_text(out)
print('index.html', len(out), 'bytes')
