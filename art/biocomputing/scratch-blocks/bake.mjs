// Bake the captured Scratch Blocks SVG paths, without any editor JS at runtime.
// Usage: node art/biocomputing/scratch-blocks/bake.mjs [playwright module path]
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';

const { chromium } = await import(process.argv[2] ? pathToFileURL(process.argv[2]).href : 'playwright');
const source = JSON.parse(await readFile(new URL('source.json', import.meta.url)));
const from = name => [source[name].fill, source[name].stroke];
// Keep this order in sync with ScratchBlockArt's atlas rows.
const palettes = [
  from('event_whenflagclicked'), from('control_wait'), from('operator_add'),
  from('data_variable'), ['#ff661a', '#cc4d00'], from('looks_say'),
  from('sensing_answer'), from('motion_movesteps'), from('sound_stopallsounds'),
  ['#ff6680', '#ff3355']
];
// Layout matches ScratchBlockArt: shape, capture, crop offset in Scratch units.
const shapes = [
  ['event_whenflagclicked', 0, 0], ['control_wait', 0, 0],
  ['control_repeat', 0, 0], ['control_repeat', 0, 80],
  ['operator_add', 0, 0], ['operator_lt', 0, 0],
  ['math_number', 0, 0], ['control_repeat', 0, 59],
  ['procedures_definition', 0, 0], ['procedures_prototype', 0, 0], ['control_wait', 0, 0, true],
  ['control_if_else', 0, 68]
];
const pitchX = 88, pitchY = 48;
const crops = [[75, 39], [84, 30], [82, 30], [82, 18], [60, 22], [76, 22], [22, 18], [18, 6], [78, 48], [76, 30], [84, 26], [82, 24]];
const width = pitchX * shapes.length, height = pitchY * palettes.length;
let defs = '', paths = '';
for (const [col, [name, cropX, cropY, cap]] of shapes.entries()) {
  const [w, h] = crops[col];
  // The source repeat's inner wall is at x=16. Native child rows begin
  // at 16 GUI units, so the wall and connector move +16 SVG units at 1/2 scale.
  const raw = source[name].d;
  // Compact the long straight spans of the captured two-argument definition;
  // the cap arcs, stack connectors and inset styling are unchanged.
  const mouth = name === 'control_repeat' || name === 'control_if_else';
  const path = cap ? raw.replace(/h -110\.3515625.*h -8 (?=a 4 4 0 0,1 -4,-4 z)/, 'h -154.3515625 ')
    : mouth ? raw.replaceAll('H 64 ', 'H 80 ')
    : name === 'procedures_definition' ? raw.replace('343.9609375', '112').replace('331.9609375', '100')
    : name === 'procedures_prototype' ? raw.replaceAll('262.2890625', '96') : raw;
  if (mouth && path === raw) throw new Error('Statement mouth anchor not found');
  if (cap && path === raw) throw new Error('Stack bottom connector not found');
  for (const [row, [fill, stroke]] of palettes.entries()) {
    const id = `c-${col}-${row}`, x = col * pitchX, y = row * pitchY;
    defs += `<clipPath id="${id}"><rect x="${x}" y="${y}" width="${w}" height="${h}"/></clipPath>`;
    const actualFill = name.startsWith('procedures_') ? source[name].fill : col === 6 ? '#fff' : fill;
    paths += `<g clip-path="url(#${id})"><path transform="translate(${x+1-cropX/2} ${y+1-cropY/2}) scale(.5)" d="${path}" fill="${actualFill}" stroke="${stroke}" stroke-width="1"/></g>`;
  }
}
const dest = new URL('../pack/assets/create_biotech/textures/gui/biocomputing/', import.meta.url);
await mkdir(dest, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome' });
try {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 2 });
  await page.setContent(`<body style="margin:0"><svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><defs>${defs}</defs>${paths}</svg></body>`);
  await page.screenshot({ path: fileURLToPath(new URL('scratch_blocks.png', dest)), omitBackground: true });
  await writeFile(new URL('scratch_blocks.svg', import.meta.url),
    `<!-- Adapted from Scratch Blocks editor paths; see README.md. -->\n<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><defs>${defs}</defs>${paths}</svg>\n`);
} finally {
  await browser.close();
}
