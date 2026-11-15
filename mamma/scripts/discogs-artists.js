// Arma app/src/main/assets/artists.json: cantantes italianos de todas las epocas, sus discos
// y sus canciones mas conocidas con el video de YouTube que figura en Discogs.
// Tambien escribe assets/keys.js con las claves de Discogs (secretos DISCOGS_KEY / DISCOGS_SECRET),
// igual que FIYODIGGER, para buscar desde la app.
const fs = require('fs');
const path = require('path');

const ARTISTS = [
  // los de mama primero
  'Eros Ramazzotti', 'Laura Pausini', 'Andrea Bocelli',
  // anos 50 y 60
  'Domenico Modugno', 'Mina', 'Adriano Celentano', 'Gino Paoli', 'Sergio Endrigo', 'Peppino Di Capri',
  'Fred Bongusto', 'Rita Pavone', 'Gianni Morandi', 'Gigliola Cinquetti', 'Bobby Solo', 'Little Tony',
  'Ornella Vanoni', 'Massimo Ranieri', 'Patty Pravo', 'Orietta Berti', 'Iva Zanicchi', 'Fausto Leali',
  // anos 70
  'Lucio Battisti', 'Nicola Di Bari', 'Mia Martini', 'Marcella Bella', 'Drupi', 'Riccardo Cocciante',
  'Claudio Baglioni', 'Lucio Dalla', 'Fabrizio De André', 'Pooh', 'Umberto Tozzi', 'Al Bano & Romina Power',
  'Raffaella Carrà', 'Ricchi E Poveri', 'Toto Cutugno', 'Loredana Bertè', 'Edoardo Bennato', 'Paolo Conte',
  // anos 80
  'Pupo', 'Matia Bazar', 'Franco Battiato', 'Vasco Rossi', 'Zucchero', 'Gianna Nannini', 'Anna Oxa',
  'Enrico Ruggeri', 'Raf', 'Amedeo Minghi',
  // anos 90 en adelante
  'Mietta', 'Marco Masini', 'Nek', '883', 'Tiziano Ferro', 'Il Volo'
].filter((x, i, a) => a.indexOf(x) === i);
// IDs de Discogs ya verificados (sirven para armar el catalogo sin claves)
const KNOWN = {
  'Eros Ramazzotti': 201878, 'Laura Pausini': 80894, 'Andrea Bocelli': 318300, 'Tiziano Ferro': 286825,
  'Zucchero': 176435, 'Il Volo': 2086557, 'Nek': 168621, 'Gianna Nannini': 175006, 'Adriano Celentano': 36543,
  'Toto Cutugno': 215246, 'Al Bano & Romina Power': 303666, 'Umberto Tozzi': 176185, 'Raffaella Carrà': 302018,
  'Ricchi E Poveri': 310136, 'Claudio Baglioni': 638411, 'Riccardo Cocciante': 399868, 'Lucio Dalla': 207470,
  'Mina': 319972, 'Marco Masini': 268725, 'Pooh': 380875,
  'Raf': 246232, 'Mietta': 299780, 'Amedeo Minghi': 490247, 'Matia Bazar': 136886, 'Drupi': 327416,
  'Gigliola Cinquetti': 464799, 'Bobby Solo': 409131, 'Patty Pravo': 339934, 'Massimo Ranieri': 319960,
  'Fausto Leali': 327903, 'Gino Paoli': 319978, 'Ornella Vanoni': 361057, 'Gianni Morandi': 410333,
  'Little Tony': 217518, 'Domenico Modugno': 311963, 'Peppino Di Capri': 492344, 'Fred Bongusto': 9772,
  'Sergio Endrigo': 319968, 'Rita Pavone': 380929, 'Nicola Di Bari': 851817, 'Iva Zanicchi': 949064,
  'Orietta Berti': 920359, '883': 137933, 'Loredana Bertè': 366682, 'Edoardo Bennato': 53807,
  'Franco Battiato': 114390, 'Lucio Battisti': 250902, 'Anna Oxa': 297663, 'Pupo': 345918,
  'Mia Martini': 556869, 'Vasco Rossi': 130185, 'Enrico Ruggeri': 503126, 'Paolo Conte': 273695,
  'Marcella Bella': 503123, 'Fabrizio De André': 660740,
};
const ALBUMS_WITH_SONGS = 14;   // discos por cantante de donde se sacan canciones
const SONGS_PER_ALBUM = 5;

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
  for (let page = 1; page <= (KEY ? 3 : 2); page++) {
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

const BAD = /christmas|natale|navidad|xmas|jingle|full album|album completo|álbum completo|intervista|interview|karaoke|instrumental|cover by|tutorial|reaction|lyrics video by|8d audio|slowed|remix|medley/i;
function ytId(u) { const m = /(?:v=|youtu\.be\/|embed\/)([\w-]{11})/.exec(u || ''); return m ? m[1] : null; }
function cleanTitle(t, artist) {
  let s = String(t || '');
  const a = norm(artist).split(' ')[0];
  s = s.replace(/\((official|video|audio|videoclip|lyric|testo|hd|hq|remaster)[^)]*\)|\[(official|video|audio|hd|hq|remaster)[^\]]*\]/gi, '');
  const parts = s.split(/\s[-–—]\s/);
  if (parts.length > 1 && norm(parts[0]).includes(a)) s = parts.slice(1).join(' - ');
  s = s.replace(/\[[^\]]*\]/g, '').replace(/\((19|20)\d\d\)/g, '');
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
      const songs = await songsOf(name, alb);
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
