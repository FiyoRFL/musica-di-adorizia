// Arma app/src/main/assets/artists.json: cantantes italianos de todas las epocas, sus discos
// y sus canciones mas conocidas con el video de YouTube que figura en Discogs.
// Tambien escribe assets/keys.js con las claves de Discogs (secretos DISCOGS_KEY / DISCOGS_SECRET),
// igual que FIYODIGGER, para buscar desde la app.
const fs = require('fs');
const path = require('path');

const ARTISTS = [
  // los de mama primero
  'Eros Ramazzotti', 'Laura Pausini', 'Andrea Bocelli',
  // clasicos de siempre
  'Domenico Modugno', 'Mina', 'Adriano Celentano', 'Lucio Battisti', 'Gianni Morandi', 'Rita Pavone',
  'Gigliola Cinquetti', 'Massimo Ranieri', 'Little Tony', 'Bobby Solo', 'Nicola Di Bari', 'Ornella Vanoni',
  'Gino Paoli', 'Peppino Di Capri', 'Patty Pravo', 'Iva Zanicchi', 'Fred Bongusto',
  'Toto Cutugno', 'Al Bano & Romina Power', 'Umberto Tozzi', 'Raffaella Carrà', 'Ricchi E Poveri',
  'Claudio Baglioni', 'Riccardo Cocciante', 'Lucio Dalla', 'Pooh', 'Pupo', 'Matia Bazar', 'Drupi',
  'Franco Battiato', 'Fiorella Mannoia', 'Antonello Venditti', 'Renato Zero', 'Vasco Rossi', 'Paolo Conte',
  'Zucchero', 'Gianna Nannini', 'Marco Masini', 'Nek', 'Biagio Antonacci', 'Tiziano Ferro', 'Giorgia',
  'Alessandra Amoroso', 'Il Volo', 'Laura Pausini'
].filter((x, i, a) => a.indexOf(x) === i);
const KNOWN = { 'Eros Ramazzotti': 201878, 'Laura Pausini': 80894 };
const ALBUMS_WITH_SONGS = 10;   // discos por cantante de donde se sacan canciones
const SONGS_PER_ALBUM = 4;

const KEY = (process.env.DISCOGS_KEY || '').trim();
const SECRET = (process.env.DISCOGS_SECRET || '').trim();
const UA = 'MusicaDeMama-build/1.0';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const norm = s => (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/\s*\(\d+\)$/, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

fs.writeFileSync(path.join(assets, 'keys.js'),
  'window.DGK = ' + JSON.stringify(KEY) + ';\nwindow.DGS = ' + JSON.stringify(SECRET) + ';\n');

async function get(url) {
  const auth = KEY ? (url.includes('?') ? '&' : '?') + `key=${KEY}&secret=${SECRET}` : '';
  for (let i = 0; i < 5; i++) {
    const r = await fetch(url + auth, { headers: { 'User-Agent': UA } });
    if (r.status === 429) { await sleep(20000); continue; }
    if (!r.ok) throw new Error(r.status + ' ' + url);
    const left = +r.headers.get('x-discogs-ratelimit-remaining');
    await sleep(left && left < 5 ? 8000 : (KEY ? 1050 : 2600));
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
      const st = (r.stats && (r.stats.community || r.stats.user)) || {};
      out.push({ id: r.id, title: r.title, year: r.year, cover: r.thumb || '', pop: (st.in_collection || 0) + (st.in_wantlist || 0) });
    }
    if (!d.pagination || page >= d.pagination.pages) break;
  }
  return out;
}

const BAD = /full album|album completo|álbum completo|intervista|interview|karaoke|instrumental|cover by|tutorial|reaction|lyrics video by|8d audio|slowed|remix|medley/i;
function ytId(u) { const m = /(?:v=|youtu\.be\/|embed\/)([\w-]{11})/.exec(u || ''); return m ? m[1] : null; }
function cleanTitle(t, artist) {
  let s = String(t || '');
  const a = norm(artist).split(' ')[0];
  s = s.replace(/\((official|video|audio|videoclip|lyric|testo|hd|hq|remaster)[^)]*\)|\[(official|video|audio|hd|hq|remaster)[^\]]*\]/gi, '');
  const parts = s.split(/\s[-–—]\s/);
  if (parts.length > 1 && norm(parts[0]).includes(a)) s = parts.slice(1).join(' - ');
  return s.replace(/\s{2,}/g, ' ').trim();
}

async function songsOf(name, list) {
  const songs = [];
  const seen = new Set();
  // los discos mas populares (o, si no hay datos, repartidos en toda la carrera)
  let pick = list.slice();
  if (pick.some(a => a.pop)) pick.sort((x, y) => y.pop - x.pop);
  else { const st = Math.max(1, Math.floor(pick.length / ALBUMS_WITH_SONGS)); pick = pick.filter((_, i) => i % st === 0); }
  for (const al of pick.slice(0, ALBUMS_WITH_SONGS)) {
    try {
      const m = await get(`https://api.discogs.com/masters/${al.id}`);
      let n = 0;
      for (const v of m.videos || []) {
        const id = ytId(v.uri);
        if (!id || BAD.test(v.title || '') || (v.duration && (v.duration < 100 || v.duration > 480))) continue;
        const title = cleanTitle(v.title, name);
        const k = norm(title);
        if (!k || seen.has(k) || seen.has(id)) continue;
        seen.add(k); seen.add(id);
        songs.push({ y: id, t: title, al: al.title, yr: al.year });
        if (++n >= SONGS_PER_ALBUM) break;
      }
      if (!al.cover && m.images && m.images[0]) al.cover = m.images[0].uri150 || '';
    } catch (e) { console.warn('  master', al.id, e.message); }
  }
  return songs;
}

(async () => {
  const list = [];
  let total = 0;
  for (const name of ARTISTS) {
    try {
      const id = await findId(name);
      if (!id) { console.warn('sin id:', name); continue; }
      const a = await get(`https://api.discogs.com/artists/${id}`);
      if (norm(a.name) !== norm(name)) { console.warn('no coincide', name, '->', a.name); continue; }
      const img = (a.images || []).find(x => x.type === 'primary') || (a.images || [])[0];
      const alb = await albums(id);
      const songs = KEY ? await songsOf(name, alb) : [];
      total += songs.length;
      list.push({ name, id, photo: img ? (img.uri150 || img.uri) : '', albums: alb.map(({ pop, ...x }) => x), songs });
      console.log('ok', name, id, alb.length, 'discos,', songs.length, 'canciones:', songs.slice(0, 3).map(s => s.t).join(' | '));
    } catch (e) {
      console.warn('error', name, e.message);
    }
  }
  fs.writeFileSync(path.join(assets, 'artists.json'), JSON.stringify({ built: new Date().toISOString(), artists: list }));
  console.log('artists.json:', list.length, 'cantantes,', total, 'canciones');
  if (!list.length) process.exit(1);
})();
