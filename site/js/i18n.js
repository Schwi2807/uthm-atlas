// UI strings. English is the default; Bahasa Melayu via the EN/BM toggle. Place names come from the bundle.

const STR = {
  en: {
    search: 'Where to?', searchLabel: 'Search places, codes or rooms',
    recent: 'Recent', results: 'Results', noResults: 'No place matches', tryAlso: 'Try a code like G3, an acronym like FKEE, or a room name.',
    all: 'All', walkThere: 'Walk there', share: 'Share', copied: 'Link copied', showMap: 'Show on map', close: 'Close',
    verified: 'Walked & verified', draft: 'Not walked yet', draftNote: 'From OpenStreetMap. Name and position not checked on foot yet.',
    unconfirmed: 'Only known by a code or a generic name in OpenStreetMap. It may be gone, renamed or not what it seems: check before relying on it.',
    lastVerified: 'Last verified', floors: 'Floors', floor: 'Floor', ground: 'Ground', rooms: 'Rooms', entrances: 'Entrances',
    mainEntrance: 'Main entrance', stepFree: 'Step-free', inside: 'Inside this building', partOf: 'In building',
    accuracy: 'Position accuracy', storeys: (n) => `${n} storey${n === 1 ? '' : 's'}`,
    tour: 'First Week', tourTitle: 'Your first week on campus', tourStart: 'Start the tour', tourNext: 'Next', tourPrev: 'Back',
    tourDone: 'Done', tourEmpty: 'The tour is being prepared.', step: (a, b) => `Stop ${a} of ${b}`,
    locate: 'Show my location', locating: 'Finding you…', locDenied: 'Location permission denied', locOff: 'Location unavailable',
    locNote: 'Your location stays on this device. It is never stored or sent.',
    view3d: '3D view', view2d: '2D view', north: 'Reset to north', menu: 'Menu', theme: 'Theme', light: 'Light', dark: 'Dark', auto: 'Auto',
    language: 'Language', offlineReady: 'Available offline', offlineSaving: (p) => `Saving for offline ${p}%`,
    offlineNo: 'Online only', offline: 'You are offline', install: 'Install app', installIos: 'Tap Share, then "Add to Home Screen".',
    update: 'New map data is ready', reload: 'Reload', about: 'About', unofficial: 'Unofficial student-made map',
    footer: (d) => `Unofficial · surveyed on foot by Schwi2807 · last build ${d}`,
    aboutText: 'An independent, unofficial map of the UTHM Parit Raja campus, surveyed on foot with a phone. Not affiliated with or endorsed by the university. Places marked "Not walked yet" come from OpenStreetMap and may be out of date.',
    attribution: 'Map data © OpenStreetMap contributors · Basemap © Protomaps',
    places: (n) => `${n} places`, walked: (n) => `${n} walked`, sun: 'Lighting follows the real sun in Batu Pahat',
    loadError: 'Could not load the map data.', retry: 'Try again', photo: 'Photo', distance: (m) => m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`,
    filterBy: 'Filter places', clearFilter: 'Show everything', category: 'Category',
  },
  ms: {
    search: 'Nak ke mana?', searchLabel: 'Cari tempat, kod atau bilik',
    recent: 'Terkini', results: 'Hasil', noResults: 'Tiada tempat yang sepadan', tryAlso: 'Cuba kod seperti G3, singkatan seperti FKEE, atau nama bilik.',
    all: 'Semua', walkThere: 'Jalan ke sana', share: 'Kongsi', copied: 'Pautan disalin', showMap: 'Tunjuk di peta', close: 'Tutup',
    verified: 'Disemak berjalan kaki', draft: 'Belum disemak', draftNote: 'Daripada OpenStreetMap. Nama dan kedudukan belum disemak secara berjalan kaki.',
    unconfirmed: 'Hanya dikenali melalui kod atau nama umum dalam OpenStreetMap. Ia mungkin sudah tiada, ditukar nama atau berbeza: semak dahulu sebelum bergantung padanya.',
    lastVerified: 'Kali terakhir disemak', floors: 'Aras', floor: 'Aras', ground: 'Bawah', rooms: 'Bilik', entrances: 'Pintu masuk',
    mainEntrance: 'Pintu utama', stepFree: 'Tanpa tangga', inside: 'Di dalam bangunan ini', partOf: 'Dalam bangunan',
    accuracy: 'Ketepatan kedudukan', storeys: (n) => `${n} tingkat`,
    tour: 'Minggu Pertama', tourTitle: 'Minggu pertama anda di kampus', tourStart: 'Mula lawatan', tourNext: 'Seterusnya', tourPrev: 'Kembali',
    tourDone: 'Selesai', tourEmpty: 'Lawatan sedang disediakan.', step: (a, b) => `Hentian ${a} daripada ${b}`,
    locate: 'Tunjuk lokasi saya', locating: 'Mencari anda…', locDenied: 'Kebenaran lokasi ditolak', locOff: 'Lokasi tidak tersedia',
    locNote: 'Lokasi anda kekal dalam peranti ini. Ia tidak disimpan atau dihantar.',
    view3d: 'Paparan 3D', view2d: 'Paparan 2D', north: 'Hala ke utara', menu: 'Menu', theme: 'Tema', light: 'Cerah', dark: 'Gelap', auto: 'Auto',
    language: 'Bahasa', offlineReady: 'Boleh guna tanpa talian', offlineSaving: (p) => `Menyimpan untuk luar talian ${p}%`,
    offlineNo: 'Dalam talian sahaja', offline: 'Anda di luar talian', install: 'Pasang aplikasi', installIos: 'Ketik Kongsi, kemudian "Add to Home Screen".',
    update: 'Data peta baharu sedia', reload: 'Muat semula', about: 'Perihal', unofficial: 'Peta tidak rasmi buatan pelajar',
    footer: (d) => `Tidak rasmi · diukur berjalan kaki oleh Schwi2807 · binaan terakhir ${d}`,
    aboutText: 'Peta bebas dan tidak rasmi kampus UTHM Parit Raja, diukur secara berjalan kaki dengan telefon. Tidak bergabung dengan atau disokong oleh universiti. Tempat bertanda "Belum disemak" diambil daripada OpenStreetMap dan mungkin tidak terkini.',
    attribution: 'Data peta © penyumbang OpenStreetMap · Peta asas © Protomaps',
    places: (n) => `${n} tempat`, walked: (n) => `${n} disemak`, sun: 'Pencahayaan mengikut matahari sebenar di Batu Pahat',
    loadError: 'Data peta tidak dapat dimuatkan.', retry: 'Cuba lagi', photo: 'Foto', distance: (m) => m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`,
    filterBy: 'Tapis tempat', clearFilter: 'Tunjuk semua', category: 'Kategori',
  },
};

let lang = 'en';
export const getLang = () => lang;
export function setLang(l) { lang = STR[l] ? l : 'en'; document.documentElement.lang = lang === 'ms' ? 'ms' : 'en'; }
export function t(key, ...args) {
  const v = STR[lang][key] ?? STR.en[key] ?? key;
  return typeof v === 'function' ? v(...args) : v;
}
export const pname = (p) => (lang === 'ms' && p.ms) ? p.ms : p.en;
export const altName = (p) => (lang === 'ms' ? (p.ms && p.en !== p.ms ? p.en : null) : (p.ms && p.ms !== p.en ? p.ms : null));
export const catName = (c) => (c ? (lang === 'ms' ? c.ms : c.en) : '');

const MONTHS = {
  en: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'],
  ms: ['Jan', 'Feb', 'Mac', 'Apr', 'Mei', 'Jun', 'Jul', 'Ogo', 'Sep', 'Okt', 'Nov', 'Dis'],
};
export function fmtDate(iso) {
  if (!iso) return '';
  const [y, m, d] = iso.split('-').map(Number);
  return `${d} ${MONTHS[lang][m - 1]} ${y}`;
}
export function floorLabel(f) {
  if (f == null) return '';
  if (f === 0) return t('ground');
  return f < 0 ? `B${-f}` : String(f);
}
