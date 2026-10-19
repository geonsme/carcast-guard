// 把 ic_guard_foreground 的矢量设计栅格化成 PNG 预览（与设备上矢量渲染同源）
const fs = require('fs'), zlib = require('zlib');
const S = 512, K = S / 108;
const px = Buffer.alloc(S * S * 4);

const BG = [0x1e, 0x20, 0x22];
const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const G_START = hex('#84D87B'), G_MID = hex('#BAF974'), G_END = hex('#5B8557');

function lerp(a, b, t) { return a + (b - a) * t; }
function grad(t) {
  t = Math.max(0, Math.min(1, t));
  const c = t < 0.5 ? [lerp(G_START[0], G_MID[0], t * 2), lerp(G_START[1], G_MID[1], t * 2), lerp(G_START[2], G_MID[2], t * 2)]
                     : [lerp(G_MID[0], G_END[0], (t - 0.5) * 2), lerp(G_MID[1], G_END[1], (t - 0.5) * 2), lerp(G_MID[2], G_END[2], (t - 0.5) * 2)];
  return c.map(v => Math.round(v));
}
// 渐变轴：左上 -> 右下
const AX = { x0: 32, y0: 26, x1: 76, y1: 84 };
function axisT(x, y) {
  const dx = AX.x1 - AX.x0, dy = AX.y1 - AX.y0;
  return ((x - AX.x0) * dx + (y - AX.y0) * dy) / (dx * dx + dy * dy);
}
function distSeg(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  const L2 = dx * dx + dy * dy || 1e-9;
  let t = ((px - ax) * dx + (py - ay) * dy) / L2;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx, cy = ay + t * dy;
  return Math.hypot(px - cx, py - cy);
}
function strokeDist(x, y, pts, closed) {
  let m = 1e9;
  const n = pts.length, lim = closed ? n : n - 1;
  for (let i = 0; i < lim; i++) {
    const a = pts[i], b = pts[(i + 1) % n];
    m = Math.min(m, distSeg(x, y, a[0], a[1], b[0], b[1]));
  }
  return m;
}
// 盾牌轮廓（折线逼近圆角曲线）
const SHIELD = [[54,26],[76,35],[76,57],[70,69],[61,77],[54,82],[47,77],[38,69],[32,57],[32,35]];
const CHECK = [[43,54],[51,63],[66,42]];
const R = 0.21 * S, CX = S / 2, CY = S / 2;
function inRounded(x, y) {
  const dx = Math.abs(x - CX) - (S / 2 - R), dy = Math.abs(y - CY) - (S / 2 - R);
  if (dx <= 0 || dy <= 0) return true;
  return Math.hypot(Math.max(0, dx), Math.max(0, dy)) <= R;
}
const AA = 1.15; // 逻辑像素抗锯齿带宽
for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    const lx = x / K, ly = y / K;
    let col = null;
    if (inRounded(x, y)) {
      const ds = strokeDist(lx, ly, SHIELD, true) ;
      const dc = strokeDist(lx, ly, CHECK, false);
      const ws = 5.6 / 2, wc = 6.4 / 2;
      const covS = Math.max(0, Math.min(1, (ws + AA - ds) / (2 * AA)));
      const covC = Math.max(0, Math.min(1, (wc + AA - dc) / (2 * AA)));
      const cov = Math.max(covS, covC);
      if (cov > 0) {
        const c = grad(axisT(lx, ly));
        col = [
          Math.round(lerp(BG[0], c[0], cov)),
          Math.round(lerp(BG[1], c[1], cov)),
          Math.round(lerp(BG[2], c[2], cov)), 255];
      } else col = [...BG, 255];
    }
    const o = (y * S + x) * 4;
    if (col) { px[o] = col[0]; px[o+1] = col[1]; px[o+2] = col[2]; px[o+3] = col[3]; }
  }
}
// --- PNG 编码 ---
let crcT = [];
for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[n] = c >>> 0; }
function crc32(buf) { let c = 0xffffffff; for (const b of buf) c = crcT[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
const raw = Buffer.alloc(S * (S * 4 + 1));
for (let y = 0; y < S; y++) { raw[y * (S * 4 + 1)] = 0; px.copy(raw, y * (S * 4 + 1) + 1, y * S * 4, (y + 1) * S * 4); }
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4);
ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
const png = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, {level:9})), chunk('IEND', Buffer.alloc(0))]);
fs.writeFileSync(process.argv[2] || 'preview.png', png);
console.log('wrote', png.length, 'bytes');
