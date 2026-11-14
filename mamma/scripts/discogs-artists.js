// Arma app/src/main/assets/artists.json con los cantantes, sus fotos y sus discos (de Discogs).
// Se corre al compilar. Usa DISCOGS_KEY / DISCOGS_SECRET si estan (fotos y tapas);
// sin ellas igual funciona: la app pide los discos a Discogs sin token desde el celu.
const fs = require('fs');
const path = require('path');

const ARTISTS = [
  'Eros Ramazzotti', 'Laura Pausini', 'Andrea Bocelli', 'Tiziano Ferro', 'Zucchero',
  'Il Volo', 'Nek', 'Gianna Nannini', 'Adriano Celentano', 'Toto Cutugno',
  'Al Bano & Romina Power', 'Umberto Tozzi', 'Raffaella Carrà', 'Ricchi E Poveri',
  'Claudio Baglioni', 'Riccardo Cocciante', 'Lucio Dalla', 'Mina', 'Marco Masini', 'Pooh'
];
// Respaldo sin claves: IDs conocidos (se verifican por nombre antes de usarlos)
const KNOWN = { 'Eros Ramazzotti': 201878, 'Laura Pausini': 80894 };

const KEY = (process.env.DISCOGS_KEY || '').trim();
const SECRET = (process.env.DISCOGS_SECRET || '').trim();
const UA = 'MusicaDeMama-build/1.0';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const norm = s => (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/\s*\(\d+\)$/, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

async function get(url) {
  const auth = KEY ? (url.includes('?') ? '&' : '?') + `key=${KEY}&secret=${SECRET}` : '';
  for (let i = 0; i < 4; i++) {
    const r = await fetch(url + auth, { headers: { 'User-Agent': UA } });
    if (r.status === 429) { await sleep(15000); continue; }
    if (!r.ok) throw new Error(r.status + ' ' + url);
    await sleep(KEY ? 1100 : 2600);
    return r.json();
  }
  throw new Error('429 ' + url);
}

async function findId(name) {
  if (KEY) {
    const s = await get(`https://api.discogs.com/database/search?type=artist&per_page=10&q=${encodeURIComponent(name)}`);
    const hit = s.results.find(x => norm(x.title) === norm(name)) || s.results[0];
    if (hit) return hit.id;
  }
  return KNOWN[name] || null;
}

async function albums(id) {
  const out = [];
  const seen = new Set();
  for (let page = 1; page <= 3; page++) {
    const d = await get(`https://api.discogs.com/artists/${id}/releases?sort=year&sort_order=desc&per_page=100&page=${page}`);
    for (const r of d.releases || []) {
      if (r.type !== 'master' || r.role !== 'Main' || !r.year) continue;
      const k = norm(r.title);
      if (seen.has(k)) continue;
      seen.add(k);
      out.push({ id: r.id, title: r.title, year: r.year, cover: r.thumb || '' });
    }
    if (!d.pagination || page >= d.pagination.pages) break;
  }
  return out;
}

(async () => {
  const list = [];
  for (const name of ARTISTS) {
    try {
      const id = await findId(name);
      if (!id) { console.warn('sin id:', name); continue; }
      const a = await get(`https://api.discogs.com/artists/${id}`);
      if (norm(a.name) !== norm(name)) { console.warn('no coincide', name, '->', a.name); continue; }
      const img = (a.images || []).find(x => x.type === 'primary') || (a.images || [])[0];
      const alb = await albums(id);
      list.push({ name, id, photo: img ? (img.uri150 || img.uri) : '', albums: alb });
      console.log('ok', name, id, alb.length, 'discos');
    } catch (e) {
      console.warn('error', name, e.message);
    }
  }
  const out = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'artists.json');
  fs.writeFileSync(out, JSON.stringify({ built: new Date().toISOString(), artists: list }));
  console.log('artists.json:', list.length, 'cantantes');
  if (!list.length) process.exit(1);
})();
