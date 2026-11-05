// three.js-Darstellung des Spielfelds: Kacheln, X, Katzen, Hinweise und alle Effekte
// (Partikel, Schockwellen, Kamerawackeln, Sieg-Choreografie).
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { createCat, animateCat } from './catModel.js';
import { MARK_EMPTY, MARK_X, MARK_CAT, MARK_AUTO_X } from './marks.js';
import { PALETTE, X_COLORS } from '../logic/palette.js';

const PITCH = 1.1;
const TILE = 1.0;
const TILE_H = 0.22;
const TILT = 0.2; // Kameraneigung (rad) – zeigt die Kachelkanten
const MARGIN = 0.12;
// Katzen leicht nach unten versetzen: Ohren + Kameraneigung lassen sie sonst zu hoch wirken.
const CAT_Y = -0.1;
const CAT_Z = 0.3;
/** Gesamtdauer der Katzen-Landung inkl. Nachfedern. */
export const CAT_ANIM_MS = 640;
/** Zeit vom Setzen bis zum Aufprall der Katze (danach startet die X-Welle). */
export const CAT_LAND_MS = 230;
/** So lange muss man drücken, bis eine Notiz gesetzt wird. */
export const LONG_PRESS_MS = 430;
const MOUSE_SPEED = 7; // Felder pro Sekunde
const DOZE_MS = 15000; // so lange ohne Aktivität, dann dösen die Katzen
const AMBIENT_FRAME_MS = 33; // Leerlauf (Atmen, Pulsieren): etwa 30 Bilder pro Sekunde
const DRAG_MIN_PX = 10;

const REDUCED_MQ = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
let REDUCED = !!REDUCED_MQ?.matches;
REDUCED_MQ?.addEventListener?.('change', (e) => { REDUCED = e.matches; });

const COLOR_X = X_COLORS.own; // eigene X: dunkelgrau
const COLOR_AUTO_X = X_COLORS.auto; // automatische X: hellgrau
const SPARK_COLORS = ['#ffd84d', '#ffffff', '#ffb3c7', '#9fe0d8'];
const CONFETTI_COLORS = [...PALETTE.map((p) => p.hex), '#ffffff', '#ffd84d'];

// --- Easing --------------------------------------------------------------------
const easeOutCubic = (t) => 1 - Math.pow(1 - t, 3);
const easeInQuad = (t) => t * t;
const easeOutQuad = (t) => 1 - (1 - t) * (1 - t);
const easeInOutCubic = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
const easeOutBack = (t, s = 1.70158) => 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2);
const easeOutElastic = (t) => {
  if (t <= 0) return 0;
  if (t >= 1) return 1;
  return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * ((2 * Math.PI) / 3)) + 1;
};
/** Gedämpfte Schwingung: startet bei 0, schwingt `cycles`-mal und klingt ab. */
const damped = (t, cycles = 2, decay = 4.5) => Math.exp(-decay * t) * Math.sin(t * cycles * Math.PI * 2);
const rand = (a, b) => a + Math.random() * (b - a);
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

// ---------------------------------------------------------------------------------
// Partikel: ein InstancedMesh pro Sorte, Instanzen werden recycelt
// ---------------------------------------------------------------------------------

class Particles {
  constructor(scene, geo, mat, capacity, onSpawn) {
    this.onSpawn = onSpawn;
    this.mesh = new THREE.InstancedMesh(geo, mat, capacity);
    this.mesh.visible = false;
    this.mesh.frustumCulled = false;
    this.mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    this.zero = new THREE.Matrix4().makeScale(0, 0, 0);
    const white = new THREE.Color(1, 1, 1);
    for (let i = 0; i < capacity; i++) {
      this.mesh.setMatrixAt(i, this.zero);
      this.mesh.setColorAt(i, white);
    }
    this.free = [...Array(capacity).keys()];
    this.live = [];
    this.m = new THREE.Matrix4();
    this.q = new THREE.Quaternion();
    this.e = new THREE.Euler();
    this.p = new THREE.Vector3();
    this.s = new THREE.Vector3();
    this.c = new THREE.Color();
    scene.add(this.mesh);
  }

  spawn(o) {
    if (!this.free.length) {
      const old = this.live.shift();
      this.free.push(old.idx);
    }
    const idx = this.free.pop();
    this.c.set(o.color || '#ffffff');
    this.mesh.setColorAt(idx, this.c);
    this.mesh.instanceColor.needsUpdate = true;
    this.mesh.visible = true;
    this.onSpawn?.();
    this.live.push({
      idx,
      x: o.x, y: o.y, z: o.z,
      vx: o.vx || 0, vy: o.vy || 0, vz: o.vz || 0,
      gx: o.gx || 0, gy: o.gy || 0, gz: o.gz || 0,
      drag: o.drag || 0,
      life: o.life || 0.6,
      age: -(o.delay || 0),
      size: o.size || 0.1,
      aspect: o.aspect || 1,
      shape: o.shape || 'spark',
      rx: o.rx ?? Math.random() * 6, ry: o.ry ?? Math.random() * 6, rz: o.rz ?? Math.random() * 6,
      sx: o.sx ?? rand(-8, 8), sy: o.sy ?? rand(-8, 8), sz: o.sz ?? rand(-4, 4),
      flutter: o.flutter || 0,
      phase: Math.random() * 6.28,
    });
  }

  update(dt) {
    const keep = [];
    for (const p of this.live) {
      p.age += dt;
      if (p.age < 0) { keep.push(p); continue; }
      if (p.age >= p.life) {
        this.mesh.setMatrixAt(p.idx, this.zero);
        this.free.push(p.idx);
        continue;
      }
      const k = Math.exp(-p.drag * dt);
      p.vx = p.vx * k + p.gx * dt;
      p.vy = p.vy * k + p.gy * dt;
      p.vz = p.vz * k + p.gz * dt;
      p.x += (p.vx + (p.flutter ? Math.sin(p.age * 6 + p.phase) * p.flutter : 0)) * dt;
      p.y += p.vy * dt;
      p.z += p.vz * dt;
      p.rx += p.sx * dt;
      p.ry += p.sy * dt;
      p.rz += p.sz * dt;
      const t = p.age / p.life;
      let s;
      if (p.shape === 'puff') s = t < 0.2 ? easeOutCubic(t / 0.2) : 1 - easeInQuad((t - 0.2) / 0.8);
      else if (p.shape === 'confetti') s = t > 0.85 ? (1 - t) / 0.15 : 1;
      else s = t < 0.1 ? t / 0.1 : 1 - easeInQuad((t - 0.1) / 0.9);
      const size = Math.max(0.0001, p.size * s);
      this.e.set(p.rx, p.ry, p.rz);
      this.q.setFromEuler(this.e);
      this.p.set(p.x, p.y, p.z);
      this.s.set(size, size * p.aspect, size);
      this.m.compose(this.p, this.q, this.s);
      this.mesh.setMatrixAt(p.idx, this.m);
      keep.push(p);
    }
    this.live = keep;
    this.mesh.instanceMatrix.needsUpdate = true;
    this.mesh.visible = keep.length > 0;
  }

  clear() {
    for (const p of this.live) {
      this.mesh.setMatrixAt(p.idx, this.zero);
      this.free.push(p.idx);
    }
    this.live = [];
    this.mesh.instanceMatrix.needsUpdate = true;
    this.mesh.visible = false;
  }
}

/** Abgerundeter Rahmen (für Cursor und Konflikte). */
function frameGeometry(outer, inner, rOuter, rInner) {
  const rr = (pth, w, r) => {
    const h = w / 2;
    pth.moveTo(-h + r, -h);
    pth.lineTo(h - r, -h); pth.quadraticCurveTo(h, -h, h, -h + r);
    pth.lineTo(h, h - r); pth.quadraticCurveTo(h, h, h - r, h);
    pth.lineTo(-h + r, h); pth.quadraticCurveTo(-h, h, -h, h - r);
    pth.lineTo(-h, -h + r); pth.quadraticCurveTo(-h, -h, -h + r, -h);
  };
  const shape = new THREE.Shape();
  rr(shape, outer, rOuter);
  const hole = new THREE.Path();
  rr(hole, inner, rInner);
  shape.holes.push(hole);
  return new THREE.ShapeGeometry(shape, 6);
}

// ---------------------------------------------------------------------------------

export class Board3D {
  constructor(container, callbacks) {
    this.container = container;
    this.cb = callbacks;
    this.cells = [];
    this.tweens = new Map();
    this.fx = [];
    this.n = 0;
    this.hint = null;
    this.conflicts = new Set();
    this.hover = -1;
    this.drag = null;
    this.shakeAmp = 0;
    this.thump = 0;
    this.last = performance.now();
    this.lastRender = 0;
    this.lastActive = performance.now();
    this.dozeK = 0;
    this.dozing = false;
    this.mode = 'busy';
    this.looping = false;
    this.paused = false;
    this.dirty = true;
    this.calm = false;
    this.locked = false;
    this.errorReported = false;
    this.size = 0;

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.debug.checkShaderErrors = false;
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFShadowMap;
    this.renderer.setClearColor(0x000000, 0);
    container.appendChild(this.renderer.domElement);
    this.canvas = this.renderer.domElement;
    this.canvas.classList.add('board-canvas');

    this.scene = new THREE.Scene();
    this.camera = new THREE.OrthographicCamera(-5, 5, 5, -5, 0.1, 100);
    this.camBase = new THREE.Vector3();

    this.scene.add(new THREE.HemisphereLight(0xffffff, 0xf3e6dc, 2.2));
    const sun = new THREE.DirectionalLight(0xffffff, 1.35);
    sun.position.set(-3, 4, 12);
    sun.castShadow = true;
    sun.shadow.mapSize.set(1024, 1024);
    sun.shadow.bias = -0.0015;
    sun.shadow.radius = 4;
    this.sun = sun;
    this.scene.add(sun);
    this.scene.add(sun.target);

    this.group = new THREE.Group();
    this.scene.add(this.group);

    this.tileGeo = new RoundedBoxGeometry(TILE, TILE, TILE_H, 3, 0.11);
    this.tileMats = []; // Pool: wird bei jedem Level wiederverwendet (kein neues Shader-Kompilieren)
    // X als eine einzige Geometrie (zwei gekreuzte Balken)
    const bar = new RoundedBoxGeometry(0.56, 0.11, 0.05, 2, 0.025);
    const b1 = bar.clone().rotateZ(Math.PI / 4).translate(0, 0, 0.03);
    const b2 = bar.clone().rotateZ(-Math.PI / 4).translate(0, 0, 0.03);
    this.xGeo = mergeGeometries([b1, b2]);
    bar.dispose(); b1.dispose(); b2.dispose();
    const gap = PITCH - TILE;
    this.borderGeoV = new THREE.BoxGeometry(gap * 0.75, PITCH + gap * 0.4, TILE_H * 1.05);
    this.borderGeoH = new THREE.BoxGeometry(PITCH + gap * 0.4, gap * 0.75, TILE_H * 1.05);
    this.ghostMat = new THREE.MeshStandardMaterial({ color: '#ffffff', transparent: true, opacity: 0.5, depthWrite: false, roughness: 0.6 });
    this.xMat = new THREE.MeshStandardMaterial({ color: COLOR_X, roughness: 0.55 });
    this.xAutoMat = new THREE.MeshStandardMaterial({ color: COLOR_AUTO_X, roughness: 0.65 });
    this.xHintMat = new THREE.MeshStandardMaterial({ color: '#d4343a', emissive: '#7a0d12', roughness: 0.5 });
    this.ringGeo = new THREE.TorusGeometry(0.36, 0.045, 12, 48);
    this.ringMat = new THREE.MeshBasicMaterial({ color: '#fff7c2', transparent: true, opacity: 0.95 });
    this.waveGeo = new THREE.RingGeometry(0.8, 1, 48);
    this.borderMat = new THREE.MeshStandardMaterial({ color: '#4a312b', roughness: 0.8 });
    this.pawMat = new THREE.MeshStandardMaterial({ color: '#6b4a40', roughness: 0.7 });
    this.lpRingGeo = new THREE.RingGeometry(0.44, 0.52, 48);
    this.lpRingMat = new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0, depthWrite: false });
    // Tastatur-Cursor zweifarbig: dunkel außen, hell innen – auf jeder Kachelfarbe sichtbar
    this.cursorOuterGeo = frameGeometry(1.16, 1.02, 0.18, 0.14);
    this.cursorInnerGeo = frameGeometry(1.02, 0.9, 0.14, 0.1);
    this.cursorOuterMat = new THREE.MeshBasicMaterial({ color: '#2e2c31', depthWrite: false });
    this.cursorInnerMat = new THREE.MeshBasicMaterial({ color: '#fff7c2', depthWrite: false });
    // Konflikt: roter Rahmen, unabhängig von der Kachelfarbe
    this.conflictGeo = frameGeometry(1.08, 0.88, 0.16, 0.1);
    this.conflictMat = new THREE.MeshBasicMaterial({ color: '#e0262d', transparent: true, opacity: 0.95, depthWrite: false });
    this.conflictObjs = [];
    this.skin = 'smoking';
    this.patternTextures = new Map();
    this.cursor = -1;
    this.lp = null;
    this.mouseRun = null;

    // Partikelsysteme
    const wake = () => this.invalidate();
    this.puffs = new Particles(
      this.scene,
      new THREE.IcosahedronGeometry(1, 1),
      new THREE.MeshStandardMaterial({ roughness: 1, transparent: true, opacity: 0.92 }),
      160,
      wake,
    );
    this.sparks = new Particles(this.scene, new THREE.OctahedronGeometry(1, 0), new THREE.MeshBasicMaterial(), 260, wake);
    this.confetti = new Particles(
      this.scene,
      new THREE.PlaneGeometry(1, 0.6),
      new THREE.MeshBasicMaterial({ side: THREE.DoubleSide }),
      420,
      wake,
    );

    this.raycaster = new THREE.Raycaster();
    this.plane = new THREE.Plane(new THREE.Vector3(0, 0, 1), -TILE_H);
    this.tmpV = new THREE.Vector2();
    this.tmpP = new THREE.Vector3();

    this.bindInput();
    // Größe erst im nächsten Bild anpassen (keine ResizeObserver-Schleife mit dem Layout)
    this.resizeObserver = new ResizeObserver(() => {
      cancelAnimationFrame(this.resizeRaf);
      this.resizeRaf = requestAnimationFrame(() => this.resize());
    });
    this.resizeObserver.observe(container);
    this.watchPixelRatio();
    this.resize();
    this.invalidate();
  }

  /** Kann dieser Browser WebGL? */
  static supported() {
    try {
      const cv = document.createElement('canvas');
      return !!(window.WebGLRenderingContext && (cv.getContext('webgl2') || cv.getContext('webgl')));
    } catch {
      return false;
    }
  }

  /** Ruhige Animationen (Einstellung) – zusätzlich zu prefers-reduced-motion. */
  setCalm(v) {
    this.calm = !!v;
    this.invalidate();
  }

  get reduced() {
    return REDUCED || this.calm;
  }

  /** Zoom/Monitorwechsel: neue Pixeldichte übernehmen. */
  watchPixelRatio() {
    if (typeof matchMedia !== 'function') return;
    this.dprMq?.removeEventListener?.('change', this.dprListener);
    this.dprMq = matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`);
    this.dprListener = () => { this.resize(true); this.watchPixelRatio(); };
    this.dprMq.addEventListener?.('change', this.dprListener);
  }

  // ---------------------------------------------------------------------------
  // Zeichnen nur bei Bedarf
  // ---------------------------------------------------------------------------

  /** Etwas hat sich geändert → (wieder) zeichnen. */
  invalidate() {
    this.dirty = true;
    if (this.paused || this.looping) return;
    this.looping = true;
    this.last = performance.now();
    this.renderer.setAnimationLoop(() => this.frame());
  }

  /** Aktivität: weckt dösende Katzen und zeichnet neu. */
  touch() {
    this.lastActive = performance.now();
    if (this.dozing) {
      this.dozing = false;
      const k0 = this.dozeK;
      this.tween('doze', 280, (p) => { this.dozeK = k0 * (1 - easeOutQuad(p)); }, () => { this.dozeK = 0; });
    }
    this.invalidate();
  }

  stopLoop() {
    if (!this.looping) return;
    this.looping = false;
    this.renderer.setAnimationLoop(null);
  }

  /** Menü oder verborgener Tab: Schleife ganz anhalten. */
  pause() {
    this.paused = true;
    this.cancelLongPress();
    this.drag = null;
    this.stopLoop();
  }

  resume() {
    if (!this.paused) return;
    this.paused = false;
    this.touch();
  }

  reportError(err) {
    if (this.errorReported) return;
    this.errorReported = true;
    this.cb.onError?.(err);
  }

  // ---------------------------------------------------------------------------
  // Aufbau
  // ---------------------------------------------------------------------------

  /** opts: { borders, patterns, paletteIdx: Palettenindex je Region } */
  setLevel(level, colors, opts = {}) {
    this.stopMouse();
    this.cursor = -1;
    this.cursorObj = null;
    this.clearHint();
    this.setConflicts([]);
    this.dozing = false;
    this.dozeK = 0;
    this.lastActive = performance.now();
    this.puffs.clear();
    this.sparks.clear();
    this.confetti.clear();
    for (const f of this.fx) f.dispose?.();
    this.fx = [];
    this.conflicts.clear();
    this.group.clear();
    this.tweens.clear();
    this.cells = [];
    const n = level.n;
    this.n = n;
    this.regions = level.regions;
    this.colors = colors;
    const half = ((n - 1) / 2) * PITCH;
    for (let i = 0; i < n * n; i++) {
      const r = Math.floor(i / n);
      const c = i % n;
      if (!this.tileMats[i]) this.tileMats[i] = new THREE.MeshStandardMaterial({ roughness: 0.82, metalness: 0 });
      const mat = this.tileMats[i];
      mat.color.set(colors[level.regions[i]]);
      mat.emissive.setRGB(0, 0, 0);
      const tile = new THREE.Mesh(this.tileGeo, mat);
      tile.position.set(c * PITCH - half, half - r * PITCH, TILE_H / 2);
      tile.receiveShadow = true;
      tile.castShadow = false; // flache Kacheln: Schatten fallen ohnehin nur auf den Rand
      this.group.add(tile);
      const holder = new THREE.Group();
      holder.position.set(tile.position.x, tile.position.y, TILE_H);
      this.group.add(holder);
      this.cells.push({
        tile, holder, r, c,
        state: MARK_EMPTY, x: null, cat: null, paw: null,
        press: 0, sq: 0, hop: 0, lift: 0, glow: 0, flash: 0,
        introZ: 0, introS: 1, rot: 0,
      });
    }
    this.borders = new THREE.Group();
    for (let i = 0; i < n * n; i++) {
      const r = Math.floor(i / n);
      const c = i % n;
      const p = this.cells[i].tile.position;
      if (c < n - 1 && level.regions[i] !== level.regions[i + 1]) {
        const m = new THREE.Mesh(this.borderGeoV, this.borderMat);
        m.position.set(p.x + PITCH / 2, p.y, TILE_H * 0.52);
        this.borders.add(m);
      }
      if (r < n - 1 && level.regions[i] !== level.regions[i + n]) {
        const m = new THREE.Mesh(this.borderGeoH, this.borderMat);
        m.position.set(p.x, p.y - PITCH / 2, TILE_H * 0.52);
        this.borders.add(m);
      }
    }
    this.group.add(this.borders);
    this.setShowBorders(opts.borders);
    this.paletteIdx = opts.paletteIdx || null;
    this.setPatterns(opts.patterns);

    const ext = (n * PITCH) / 2 + 1;
    const sc = this.sun.shadow.camera;
    sc.left = -ext; sc.right = ext; sc.top = ext; sc.bottom = -ext; sc.near = 0.5; sc.far = 40;
    sc.updateProjectionMatrix();

    // Einflug: Kacheln fallen diagonal versetzt aufs Brett und wackeln nach.
    if (!this.reduced) this.cells.forEach((cell, i) => {
      const delay = (cell.r + cell.c) * 28 + Math.random() * 40;
      const r0 = rand(-0.5, 0.5);
      cell.introZ = 2.4;
      cell.introS = 0.001;
      cell.rot = r0;
      this.tween(`in${i}`, 380, (p) => {
        const e = easeInQuad(p);
        cell.introZ = 2.4 * (1 - e);
        cell.introS = Math.max(0.001, 0.45 + 0.55 * easeOutCubic(p));
        cell.rot = r0 * (1 - easeOutCubic(p));
      }, () => {
        cell.introZ = 0; cell.introS = 1; cell.rot = 0;
        this.wobble(i, 0.2);
      }, delay);
    });
    this.resize(true);
    this.warmUp();
    this.touch();
  }

  /** Einmalig alle Shader vorab übersetzen (erste Katze, X, Hinweis …) – kein Ruckler beim ersten Mal. */
  warmUp() {
    if (this.warmed) return;
    this.warmed = true;
    const g = new THREE.Group();
    g.position.set(1000, 1000, 0);
    const cat = createCat(this.skin);
    g.add(cat);
    for (const m of [this.xMat, this.xAutoMat, this.xHintMat]) g.add(new THREE.Mesh(this.xGeo, m));
    g.add(new THREE.Mesh(this.ringGeo, this.ringMat));
    g.add(new THREE.Mesh(this.waveGeo, new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false })));
    g.add(this.makePaw());
    g.add(new THREE.Mesh(this.conflictGeo, this.conflictMat));
    g.add(new THREE.Mesh(this.cursorOuterGeo, this.cursorOuterMat));
    g.add(new THREE.Mesh(this.lpRingGeo, this.lpRingMat));
    g.add(this.getMouse());
    this.scene.add(g);
    const done = () => this.scene.remove(g);
    try {
      const pr = this.renderer.compileAsync?.(this.scene, this.camera);
      if (pr && typeof pr.then === 'function') pr.then(done, done);
      else { this.renderer.compile(this.scene, this.camera); done(); }
    } catch {
      done();
    }
  }

  setShowBorders(v) {
    if (this.borders) this.borders.visible = !!v;
    this.invalidate();
  }

  resize(force = false) {
    const cw = this.container.clientWidth;
    if (!cw) return; // versteckt: Puffer nicht neu anlegen
    const w = Math.max(10, cw);
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    if (w !== this.size || dpr !== this.renderer.getPixelRatio() || force) {
      this.size = w;
      this.renderer.setPixelRatio(dpr);
      this.renderer.setSize(w, w, false);
      this.canvas.style.width = `${w}px`;
      this.canvas.style.height = `${w}px`;
    }
    const boardW = (this.n || 5) * PITCH - (PITCH - TILE);
    const s = boardW / 2 + MARGIN;
    const cam = this.camera;
    cam.left = -s; cam.right = s; cam.top = s; cam.bottom = -s;
    const D = 30;
    // Blickpunkt so verschieben, dass Oberseiten + sichtbare Kachelkanten mittig liegen
    const ty = (TILE_H * Math.sin(TILT)) / 2 / Math.cos(TILT);
    cam.position.set(0, ty - Math.sin(TILT) * D, Math.cos(TILT) * D);
    cam.up.set(0, 1, 0);
    cam.lookAt(0, ty, 0);
    cam.updateProjectionMatrix();
    this.camBase.copy(cam.position);
    this.invalidate();
    this.cb.onResize?.();
  }

  /** Mittelpunkt eines Feldes (Oberseite) in Weltkoordinaten. */
  cellPos(i) {
    const p = this.cells[i].tile.position;
    return { x: p.x, y: p.y, z: TILE_H };
  }

  dist(a, b) {
    const A = this.cells[a];
    const B = this.cells[b];
    return Math.hypot(A.r - B.r, A.c - B.c);
  }

  // ---------------------------------------------------------------------------
  // Eingabe
  // ---------------------------------------------------------------------------

  pick(ev) {
    const rect = this.canvas.getBoundingClientRect();
    this.tmpV.set(((ev.clientX - rect.left) / rect.width) * 2 - 1, -((ev.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(this.tmpV, this.camera);
    if (!this.raycaster.ray.intersectPlane(this.plane, this.tmpP)) return -1;
    const n = this.n;
    const half = (n * PITCH) / 2;
    const c = Math.floor((this.tmpP.x + half) / PITCH);
    const r = Math.floor((half - this.tmpP.y) / PITCH);
    if (r < 0 || c < 0 || r >= n || c >= n) return -1;
    return r * n + c;
  }

  bindInput() {
    const el = this.canvas;
    el.addEventListener('pointerdown', (ev) => {
      // Solange ein Finger zieht, zählen weitere Finger nicht.
      if (this.drag && ev.pointerId !== this.drag.id) return;
      if (this.locked) return;
      const cell = this.pick(ev);
      if (cell < 0) return;
      ev.preventDefault();
      if (document.activeElement !== el) el.focus({ preventScroll: true });
      this.touch();
      this.cb.onPointerUsed?.();
      // Rechtsklick = Notiz (wie langes Drücken)
      if (ev.pointerType === 'mouse' && ev.button === 2) {
        this.pressCell(cell, 0.6);
        this.cb.onLongPress?.(cell);
        return;
      }
      if (ev.button > 0) return;
      try { el.setPointerCapture(ev.pointerId); } catch { /* egal */ }
      this.drag = { start: cell, last: cell, moved: false, id: ev.pointerId, long: false, x: ev.clientX, y: ev.clientY };
      this.lp = { cell, t0: performance.now(), ring: null };
      this.pressCell(cell);
    });
    el.addEventListener('pointermove', (ev) => {
      const cell = this.pick(ev);
      if (!this.drag) {
        const h = ev.pointerType === 'mouse' && !this.locked ? cell : -1;
        if (h !== this.hover) { this.hover = h; this.invalidate(); }
        return;
      }
      const d = this.drag;
      if (ev.pointerId !== d.id || cell < 0 || cell === d.last || d.long) return;
      if (!d.moved) {
        // Erst ab einem Mindestweg wird aus dem Tipp ein Ziehen (wackelnde Finger an Feldgrenzen).
        const cellPx = this.canvas.clientWidth / Math.max(1, this.n);
        if (Math.hypot(ev.clientX - d.x, ev.clientY - d.y) < Math.max(DRAG_MIN_PX, cellPx * 0.3)) return;
        this.cancelLongPress();
        d.moved = true;
        this.cb.onDragStart?.(d.start);
      }
      // Übersprungene Felder (schnelles Wischen) auf der Linie dazwischen auffüllen
      for (const c of this.lineCells(d.last, cell)) {
        this.pressCell(c);
        this.cb.onDragEnter?.(c);
      }
      d.last = cell;
      this.touch();
    });
    const end = (ev, cancelled) => {
      if (!this.drag || ev.pointerId !== this.drag.id) return;
      const d = this.drag;
      this.drag = null;
      this.cancelLongPress();
      this.invalidate();
      if (d.long) return;
      if (d.moved) this.cb.onDragEnd?.();
      else if (!cancelled) this.cb.onTap?.(d.start, ev.timeStamp);
    };
    el.addEventListener('pointerup', (ev) => end(ev, false));
    el.addEventListener('pointercancel', (ev) => end(ev, true));
    el.addEventListener('pointerleave', () => { if (this.hover !== -1) { this.hover = -1; this.invalidate(); } });
    el.addEventListener('contextmenu', (ev) => ev.preventDefault());
  }

  /** Felder auf der Rasterlinie von a (ausschließlich) bis b (einschließlich). */
  lineCells(a, b) {
    const n = this.n;
    let r0 = Math.floor(a / n);
    let c0 = a % n;
    const r1 = Math.floor(b / n);
    const c1 = b % n;
    const out = [];
    const dr = Math.abs(r1 - r0);
    const dc = Math.abs(c1 - c0);
    const sr = r0 < r1 ? 1 : -1;
    const sc = c0 < c1 ? 1 : -1;
    let err = dc - dr;
    while (r0 !== r1 || c0 !== c1) {
      const e2 = 2 * err;
      if (e2 > -dr) { err -= dr; c0 += sc; }
      if (e2 < dc) { err += dc; r0 += sr; }
      out.push(r0 * n + c0);
    }
    return out;
  }

  cancelLongPress() {
    if (this.lp?.ring) this.lp.ring.parent?.remove(this.lp.ring);
    this.lp = null;
    this.invalidate();
  }

  /** Fortschrittsring beim langen Drücken; löst nach LONG_PRESS_MS die Notiz aus. */
  updateLongPress(now) {
    const lp = this.lp;
    if (!lp || !this.drag || this.drag.moved) return;
    const p = (now - lp.t0) / LONG_PRESS_MS;
    if (p > 0.25 && !lp.ring) {
      lp.ring = new THREE.Mesh(this.lpRingGeo, this.lpRingMat);
      lp.ring.position.z = 0.05;
      this.cells[lp.cell].holder.add(lp.ring);
    }
    if (lp.ring) {
      const q = Math.min(1, (p - 0.25) / 0.75);
      lp.ring.scale.setScalar(1.5 - 0.5 * easeOutCubic(q));
      this.lpRingMat.opacity = 0.85 * q;
    }
    if (p >= 1) {
      const cell = lp.cell;
      this.drag.long = true;
      this.cancelLongPress();
      this.shockwave(cell, '#ffffff', 0.9, 320);
      this.wobble(cell, 0.18);
      this.cb.onLongPress?.(cell);
    }
  }

  /** Bildschirmposition (relativ zum Canvas) der Feldmitte – für Zeigehand & Co. */
  cellScreenPos(i) {
    const { x, y } = this.cellPos(i);
    const v = new THREE.Vector3(x, y, TILE_H).project(this.camera);
    const w = this.canvas.clientWidth;
    const h = this.canvas.clientHeight;
    return { x: (v.x * 0.5 + 0.5) * w, y: (-v.y * 0.5 + 0.5) * h, size: w / (this.n * PITCH + 2 * MARGIN) };
  }

  // ---------------------------------------------------------------------------
  // Notizen (Pfotenabdruck)
  // ---------------------------------------------------------------------------

  makePaw() {
    const g = new THREE.Group();
    const sph = this.pawGeo || (this.pawGeo = new THREE.SphereGeometry(1, 16, 10));
    const add = (x, y, sx, sy) => {
      const m = new THREE.Mesh(sph, this.pawMat);
      m.position.set(x, y, 0.02);
      m.scale.set(sx, sy, 0.025);
      g.add(m);
    };
    add(0, -0.06, 0.13, 0.11);
    add(-0.125, 0.065, 0.045, 0.055);
    add(-0.045, 0.125, 0.045, 0.055);
    add(0.045, 0.125, 0.045, 0.055);
    add(0.125, 0.065, 0.045, 0.055);
    g.rotation.z = rand(-0.25, 0.25);
    return g;
  }

  setNote(i, on, animate = true) {
    const cell = this.cells[i];
    if (!cell || !!cell.paw === !!on) return;
    this.touch();
    if (on) {
      const paw = this.makePaw();
      cell.paw = paw;
      cell.holder.add(paw);
      if (animate && !this.reduced) {
        const r0 = paw.rotation.z;
        paw.scale.setScalar(0.001);
        this.tween(`paw${i}`, 380, (p) => {
          const s = p < 0.5 ? 1.6 - 0.6 * easeInQuad(p / 0.5) : 1 + 0.12 * damped((p - 0.5) / 0.5, 1, 4);
          paw.scale.set(s, s, 1);
          paw.rotation.z = r0 + 0.5 * (1 - easeOutCubic(p));
        }, () => paw.scale.setScalar(1));
        this.tween(`pawfx${i}`, 1, () => {}, () => {
          this.puffAt(i, 4, '#f3e6dc', 0.3);
          this.pressCell(i, 0.5);
        }, 190);
      }
    } else {
      const paw = cell.paw;
      cell.paw = null;
      this.tweens.delete(`paw${i}`);
      this.tweens.delete(`pawfx${i}`);
      if (!animate) { cell.holder.remove(paw); return; }
      const s0 = paw.scale.x;
      this.tween(`pawout${paw.id}`, 200, (p) => paw.scale.setScalar(Math.max(0.001, s0 * (1 - easeInQuad(p)))), () => cell.holder.remove(paw));
    }
  }

  // ---------------------------------------------------------------------------
  // Katzen-Fell
  // ---------------------------------------------------------------------------

  setSkin(id) {
    if (id === this.skin) return;
    this.skin = id;
    this.touch();
    this.cells.forEach((cell, i) => {
      if (!cell.cat) return;
      cell.holder.remove(cell.cat);
      const cat = createCat(id);
      cat.position.set(0, CAT_Y, CAT_Z);
      cell.cat = cat;
      cell.holder.add(cat);
      cat.scale.setScalar(0.001);
      this.tween(`cat${i}`, 420, (p) => cat.scale.setScalar(Math.max(0.001, easeOutBack(p))), null, i * 15);
    });
  }

  // ---------------------------------------------------------------------------
  // Muster (Barrierefreiheit)
  // ---------------------------------------------------------------------------

  patternTexture(idx) {
    if (this.patternTextures.has(idx)) return this.patternTextures.get(idx);
    const S = 128;
    const cv = document.createElement('canvas');
    cv.width = S;
    cv.height = S;
    const g = cv.getContext('2d');
    g.fillStyle = '#ffffff';
    g.fillRect(0, 0, S, S);
    g.fillStyle = 'rgba(0,0,0,0.26)';
    g.strokeStyle = 'rgba(0,0,0,0.26)';
    g.lineWidth = 7;
    const lines = (fn) => { g.beginPath(); fn(); g.stroke(); };
    switch (idx % 10) {
      case 0: for (let y = 16; y < S; y += 32) for (let x = 16; x < S; x += 32) { g.beginPath(); g.arc(x, y, 6, 0, 7); g.fill(); } break;
      case 1: lines(() => { for (let k = -S; k < S * 2; k += 32) { g.moveTo(k, S); g.lineTo(k + S, 0); } }); break;
      case 2: lines(() => { for (let y = 16; y < S; y += 32) { g.moveTo(0, y); g.lineTo(S, y); } }); break;
      case 3: for (let y = 0; y < S; y += 32) for (let x = 0; x < S; x += 32) if (((x + y) / 32) % 2 === 0) g.fillRect(x + 4, y + 4, 24, 24); break;
      case 4: lines(() => { for (let x = 16; x < S; x += 32) { g.moveTo(x, 0); g.lineTo(x, S); } }); break;
      case 5: lines(() => { for (let k = -S; k < S * 2; k += 32) { g.moveTo(k, 0); g.lineTo(k + S, S); } }); break;
      case 6: g.lineWidth = 5; for (let y = 32; y < S; y += 64) for (let x = 32; x < S; x += 64) { g.beginPath(); g.arc(x, y, 18, 0, 7); g.stroke(); } break;
      case 7: g.lineWidth = 4; lines(() => { for (let k = 16; k < S; k += 32) { g.moveTo(k, 0); g.lineTo(k, S); g.moveTo(0, k); g.lineTo(S, k); } }); break;
      case 8: g.lineWidth = 6; lines(() => { for (let y = 20; y < S; y += 32) { g.moveTo(0, y); for (let x = 0; x <= S; x += 16) g.lineTo(x, y + ((x / 16) % 2 ? -10 : 0)); } }); break;
      default: g.lineWidth = 6; lines(() => { for (let y = 32; y < S; y += 64) for (let x = 32; x < S; x += 64) { g.moveTo(x - 12, y); g.lineTo(x + 12, y); g.moveTo(x, y - 12); g.lineTo(x, y + 12); } }); break;
    }
    const tex = new THREE.CanvasTexture(cv);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = 4;
    this.patternTextures.set(idx, tex);
    return tex;
  }

  setPatterns(on) {
    this.cells.forEach((cell, i) => {
      const idx = this.paletteIdx ? this.paletteIdx[this.regions[i]] : this.regions[i];
      const map = on ? this.patternTexture(idx) : null;
      const m = cell.tile.material;
      // Shader nur neu bauen, wenn sich „mit/ohne Muster“ ändert
      if (!!m.map !== !!map) m.needsUpdate = true;
      m.map = map;
    });
    this.invalidate();
  }

  // ---------------------------------------------------------------------------
  // Tastatur-Cursor
  // ---------------------------------------------------------------------------

  setCursor(i) {
    if (this.cursorObj) this.cursorObj.parent?.remove(this.cursorObj);
    this.cursorObj = null;
    this.cursor = i;
    this.touch();
    if (i < 0 || !this.cells[i]) return;
    const g = new THREE.Group();
    const outer = new THREE.Mesh(this.cursorOuterGeo, this.cursorOuterMat);
    const inner = new THREE.Mesh(this.cursorInnerGeo, this.cursorInnerMat);
    inner.position.z = 0.002;
    g.add(outer, inner);
    g.position.z = 0.06;
    g.renderOrder = 2;
    this.cells[i].holder.add(g);
    this.cursorObj = g;
    this.wobble(i, 0.08);
  }

  // ---------------------------------------------------------------------------
  // Die Maus (Fehlersuche)
  // ---------------------------------------------------------------------------

  makeMouse() {
    const g = new THREE.Group();
    const body = new THREE.Group();
    g.add(body);
    const sph = this.pawGeo || (this.pawGeo = new THREE.SphereGeometry(1, 16, 10));
    const m = (color, rough = 0.7) => new THREE.MeshStandardMaterial({ color, roughness: rough });
    const grey = m('#a9a6ab');
    const light = m('#d9d6db');
    const pink = m('#f2a4b5');
    const black = m('#151417', 0.2);
    const add = (mat, pos, scale) => {
      const mesh = new THREE.Mesh(sph, mat);
      mesh.position.set(...pos);
      mesh.scale.set(...scale);
      mesh.castShadow = true;
      body.add(mesh);
      return mesh;
    };
    add(grey, [0, -0.04, 0.12], [0.17, 0.24, 0.13]);
    add(light, [0, -0.02, 0.16], [0.11, 0.16, 0.07]);
    add(grey, [0, 0.2, 0.15], [0.12, 0.13, 0.1]);
    const ears = [];
    for (const s of [-1, 1]) {
      ears.push(add(grey, [0.1 * s, 0.25, 0.21], [0.07, 0.06, 0.03]));
      add(pink, [0.1 * s, 0.25, 0.225], [0.045, 0.04, 0.02]);
      add(black, [0.045 * s, 0.3, 0.2], [0.018, 0.018, 0.015]);
    }
    add(pink, [0, 0.335, 0.165], [0.025, 0.022, 0.02]);
    const tailCurve = new THREE.CatmullRomCurve3([
      new THREE.Vector3(0, -0.26, 0.1), new THREE.Vector3(0.06, -0.38, 0.08),
      new THREE.Vector3(-0.04, -0.5, 0.07), new THREE.Vector3(0.05, -0.6, 0.06),
    ]);
    const tail = new THREE.Mesh(new THREE.TubeGeometry(tailCurve, 16, 0.014, 6), pink);
    body.add(tail);
    g.userData = { body, tail, ears };
    g.scale.setScalar(0.9);
    return g;
  }

  /** Die Maus wird einmal gebaut und wiederverwendet. */
  getMouse() {
    if (!this.mouseObj) this.mouseObj = this.makeMouse();
    const m = this.mouseObj;
    m.rotation.set(0, 0, 0);
    m.userData.body.position.set(0, 0, 0);
    m.userData.body.rotation.set(0, 0, 0);
    return m;
  }

  /**
   * Maus läuft vom Rand zu jedem Zielfeld (onArrive je Feld) und wieder hinaus.
   * Ohne Ziele dreht sie eine Runde übers Brett.
   */
  runMouse(targets, { onArrive, onDone } = {}) {
    this.stopMouse();
    this.touch();
    const half = (this.n * PITCH) / 2;
    const mouse = this.getMouse();
    mouse.position.set(-half - 0.6, -half + 0.4, TILE_H);
    this.scene.add(mouse);
    // Reihenfolge: jeweils das nächstgelegene Ziel
    const order = [];
    const left = [...targets];
    let cur = { x: mouse.position.x, y: mouse.position.y };
    while (left.length) {
      left.sort((a, b) => {
        const pa = this.cellPos(a);
        const pb = this.cellPos(b);
        return Math.hypot(pa.x - cur.x, pa.y - cur.y) - Math.hypot(pb.x - cur.x, pb.y - cur.y);
      });
      const nxt = left.shift();
      order.push(nxt);
      cur = this.cellPos(nxt);
    }
    const pts = order.map((i) => ({ ...this.cellPos(i), cell: i }));
    if (!pts.length) {
      // eine Runde zur Kontrolle
      const q = half * 0.55;
      pts.push({ x: -q, y: q }, { x: q, y: q }, { x: q, y: -q }, { x: 0, y: 0, cheer: true });
    }
    pts.push({ x: half + 0.8, y: half + 0.6, exit: true });
    this.mouseRun = { mouse, pts, seg: 0, from: { x: mouse.position.x, y: mouse.position.y }, t0: performance.now(), pauseUntil: 0, onArrive, onDone };
    this.shake(0.015);
  }

  stopMouse() {
    if (!this.mouseRun) return;
    this.scene.remove(this.mouseRun.mouse);
    this.mouseRun = null;
    this.invalidate();
  }

  updateMouse(now) {
    const run = this.mouseRun;
    if (!run) return;
    const { mouse } = run;
    const ud = mouse.userData;
    ud.tail.rotation.z = Math.sin(now / 70) * 0.35;
    if (now < run.pauseUntil) {
      ud.body.position.z = Math.abs(Math.sin(now / 60)) * 0.08;
      ud.ears.forEach((e, k) => { e.scale.y = 0.06 + Math.sin(now / 50 + k) * 0.01; });
      return;
    }
    const target = run.pts[run.seg];
    const dx = target.x - run.from.x;
    const dy = target.y - run.from.y;
    const dist = Math.hypot(dx, dy);
    const dur = Math.max(120, (dist / (MOUSE_SPEED * PITCH)) * 1000);
    const p = Math.min(1, (now - run.t0) / dur);
    const e = easeInOutCubic(p);
    mouse.position.x = run.from.x + dx * e;
    mouse.position.y = run.from.y + dy * e;
    mouse.rotation.z = Math.atan2(dy, dx) - Math.PI / 2;
    ud.body.position.z = Math.abs(Math.sin(now / 45)) * 0.05;
    ud.body.rotation.y = Math.sin(now / 45) * 0.15;
    if (p >= 1) {
      run.from = { x: target.x, y: target.y };
      run.t0 = now;
      run.seg++;
      if (target.cell !== undefined) {
        run.pauseUntil = now + 380;
        run.t0 = now + 380;
        this.sparksAt(target.cell, 8, ['#e5484d', '#ffffff', '#ffd84d'], 2.4, 0.05);
        this.wobble(target.cell, 0.25);
        run.onArrive?.(target.cell);
      } else if (target.cheer) {
        run.pauseUntil = now + 520;
        run.t0 = now + 520;
        const center = Math.floor(this.n / 2) * this.n + Math.floor(this.n / 2);
        this.sparksAt(center, 14, ['#ffd84d', '#ffffff', '#9fe0d8', '#ffb3c7'], 3, 0.06);
      }
      if (target.exit || run.seg >= run.pts.length) {
        const done = run.onDone;
        this.stopMouse();
        done?.();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Kachel-Bewegungen
  // ---------------------------------------------------------------------------

  /** Kachel eindrücken (strength 1 = Finger). */
  pressCell(i, strength = 1, delay = 0) {
    const cell = this.cells[i];
    if (!cell) return;
    this.tween(`press${i}`, 300, (p) => {
      cell.press = (p < 0.25 ? easeOutQuad(p / 0.25) : 1 - easeOutCubic((p - 0.25) / 0.75)) * strength;
    }, () => { cell.press = 0; }, delay);
    this.wobble(i, 0.09 * strength, delay + 60);
  }

  /** Wackelpudding: Kachel wird kurz breiter/flacher und schwingt nach. */
  wobble(i, amp, delay = 0) {
    const cell = this.cells[i];
    if (!cell || this.reduced) return;
    this.tween(`wob${i}`, 520, (p) => { cell.sq = amp * damped(p, 2, 4); }, () => { cell.sq = 0; }, delay);
  }

  hopCell(i, height, dur, delay = 0) {
    const cell = this.cells[i];
    if (!cell || this.reduced) return;
    this.tween(`hop${i}`, dur, (p) => { cell.hop = Math.sin(p * Math.PI) * height; }, () => {
      cell.hop = 0;
      this.wobble(i, 0.12);
    }, delay);
  }

  glowCell(i, amount, dur, delay = 0) {
    const cell = this.cells[i];
    if (!cell) return;
    this.tween(`glow${i}`, dur, (p) => { cell.glow = Math.sin(p * Math.PI) * amount; }, () => { cell.glow = 0; }, delay);
  }

  shake(amount) {
    if (this.reduced) return;
    this.shakeAmp = Math.max(this.shakeAmp, amount);
    this.invalidate();
  }

  // ---------------------------------------------------------------------------
  // Effekte
  // ---------------------------------------------------------------------------

  puffAt(i, count = 6, color = '#ffffff', spread = 0.45, delay = 0) {
    const { x, y } = this.cellPos(i);
    for (let k = 0; k < count; k++) {
      const a = (k / count) * Math.PI * 2 + rand(-0.3, 0.3);
      const sp = rand(0.9, 1.6);
      this.puffs.spawn({
        x: x + Math.cos(a) * spread * 0.6,
        y: y + Math.sin(a) * spread * 0.6,
        z: TILE_H + 0.15,
        vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, vz: rand(0.6, 1.4),
        drag: 5,
        life: rand(0.45, 0.65),
        size: rand(0.09, 0.15),
        shape: 'puff',
        color,
        delay: delay / 1000,
      });
    }
  }

  sparksAt(i, count = 8, colors = SPARK_COLORS, speed = 3, size = 0.06, delay = 0) {
    const { x, y } = this.cellPos(i);
    for (let k = 0; k < count; k++) {
      const a = rand(0, Math.PI * 2);
      const sp = rand(speed * 0.5, speed);
      this.sparks.spawn({
        x, y, z: TILE_H + 0.5,
        vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, vz: rand(0.5, 2),
        gy: -3.5,
        drag: 2.2,
        life: rand(0.45, 0.8),
        size: rand(size * 0.7, size * 1.3),
        aspect: 1.6,
        color: pick(colors),
        delay: delay / 1000,
      });
    }
  }

  shockwave(i, color = '#ffffff', size = 1.3, dur = 460) {
    if (this.reduced) return;
    const { x, y } = this.cellPos(i);
    const mat = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.9, depthWrite: false });
    const mesh = new THREE.Mesh(this.waveGeo, mat);
    mesh.position.set(x, y, TILE_H + 0.06);
    this.scene.add(mesh);
    const t0 = performance.now();
    this.fx.push({
      update: (now) => {
        const p = Math.min(1, (now - t0) / dur);
        const s = 0.25 + easeOutCubic(p) * size;
        mesh.scale.set(s, s, 1);
        mat.opacity = 0.9 * (1 - p);
        return p < 1;
      },
      dispose: () => { this.scene.remove(mesh); mat.dispose(); },
    });
    this.invalidate();
  }

  confettiBurst(x, y, count, dirX, dirY, power = 9) {
    for (let k = 0; k < count; k++) {
      const a = Math.atan2(dirY, dirX) + rand(-0.45, 0.45);
      const sp = rand(power * 0.55, power);
      this.confetti.spawn({
        x, y, z: 2.2 + rand(0, 1),
        vx: Math.cos(a) * sp, vy: Math.sin(a) * sp,
        gy: -7, drag: 1.6,
        life: rand(2.2, 3.2),
        size: rand(0.13, 0.2),
        aspect: 0.6,
        flutter: rand(0.5, 1.4),
        sx: rand(-10, 10), sy: rand(-10, 10), sz: rand(-3, 3),
        shape: 'confetti',
        color: pick(CONFETTI_COLORS),
        delay: rand(0, 0.12),
      });
    }
  }

  confettiRain(count) {
    const half = (this.n * PITCH) / 2;
    for (let k = 0; k < count; k++) {
      this.confetti.spawn({
        x: rand(-half * 1.1, half * 1.1), y: half + rand(0.5, half * 1.2), z: 2.2 + rand(0, 1),
        vx: rand(-0.6, 0.6), vy: rand(-2, -0.5),
        gy: -2.2, drag: 0.8,
        life: rand(2.6, 3.6),
        size: rand(0.12, 0.18),
        aspect: 0.6,
        flutter: rand(0.6, 1.5),
        shape: 'confetti',
        color: pick(CONFETTI_COLORS),
        delay: rand(0, 0.9),
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Zustand
  // ---------------------------------------------------------------------------

  /** Gleicht die Darstellung mit den Markierungen ab (mit Animationen). */
  setMarks(marks, animate = true) {
    marks.forEach((m, i) => this.setCell(i, m, animate));
  }

  /**
   * Setzt den Zustand eines Feldes.
   * delay > 0: X fällt verzögert und weich herein (Welle nach dem Setzen einer Katze).
   */
  setCell(i, state, animate = true, delay = 0) {
    const cell = this.cells[i];
    if (!cell || cell.state === state) return;
    this.touch();
    const prev = cell.state;
    cell.state = state;
    const isX = state === MARK_X || state === MARK_AUTO_X;
    const wasX = prev === MARK_X || prev === MARK_AUTO_X;
    const xMat = state === MARK_AUTO_X ? this.xAutoMat : this.xMat;

    if (wasX && !isX) this.removeX(i, animate, delay, state === MARK_CAT);
    if (prev === MARK_CAT && state !== MARK_CAT) this.removeCat(i, animate);

    if (isX) {
      if (cell.x) {
        cell.x.material = xMat;
      } else {
        const obj = this.makeX(xMat);
        cell.x = obj;
        cell.holder.add(obj);
        if (animate && delay > 0) this.dropX(i, obj, delay);
        else if (animate) this.popX(i, obj);
      }
    }
    if (state === MARK_CAT && !cell.cat) {
      const cat = createCat(this.skin);
      cat.position.set(0, CAT_Y, CAT_Z);
      cell.cat = cat;
      cell.holder.add(cat);
      if (animate) this.dropCat(i, cat);
    }
  }

  makeX(mat) {
    const x = new THREE.Mesh(this.xGeo, mat);
    x.castShadow = true;
    return x;
  }

  /** Eigenes X: federnder Plopp mit kleiner Drehung und ein paar Krümeln. */
  popX(i, obj) {
    obj.scale.setScalar(0.001);
    obj.rotation.z = 0.9;
    this.tween(`x${i}`, 420, (p) => {
      obj.scale.setScalar(Math.max(0.001, easeOutElastic(p)));
      obj.rotation.z = 0.9 * (1 - easeOutCubic(Math.min(1, p * 1.6)));
    });
    this.sparksAt(i, 4, [COLOR_X, '#8a8790'], 1.6, 0.035);
  }

  /** Automatisches X: fällt von oben aufs Feld, federt nach, Kachel wippt. */
  dropX(i, obj, delay) {
    obj.scale.setScalar(0.001);
    const land = 0.42;
    this.tween(`x${i}`, 560, (p) => {
      if (p < land) {
        const k = easeInQuad(p / land);
        obj.scale.setScalar(Math.max(0.001, (1.3 - 0.3 * k) * Math.min(1, (p / land) * 3)));
        obj.rotation.z = -0.7 * (1 - k);
        obj.position.z = 0.45 * (1 - k);
      } else {
        const q = (p - land) / (1 - land);
        obj.scale.set(1 + 0.22 * damped(q, 1.5, 4), 1 - 0.16 * damped(q, 1.5, 4), 1);
        obj.rotation.z = 0;
        obj.position.z = 0;
      }
    }, () => obj.scale.setScalar(1), delay);
    this.tween(`xland${i}`, 1, () => {}, () => {
      this.pressCell(i, 0.45);
    }, delay + 560 * land);
  }

  /**
   * X entfernen. Eigenes X wird „gezupft“ (hoch, groß, Drehung, weg),
   * automatische X schrumpfen weich (Welle zurück zur Katze).
   */
  removeX(i, animate, delay = 0, underCat = false) {
    const cell = this.cells[i];
    const obj = cell.x;
    if (!obj) return;
    cell.x = null;
    this.tweens.delete(`x${i}`);
    this.tweens.delete(`xland${i}`);
    if (!animate || underCat) {
      if (!animate) { cell.holder.remove(obj); return; }
      // unter einer landenden Katze verschwindet das X schnell
      const s0 = obj.scale.x;
      this.tween(`xout${obj.id}`, 120, (p) => obj.scale.setScalar(Math.max(0.001, s0 * (1 - p))), () => cell.holder.remove(obj));
      return;
    }
    const s0 = obj.scale.x;
    const r0 = obj.rotation.z;
    if (delay > 0) {
      this.tween(`xout${obj.id}`, 300, (p) => {
        const e = easeInOutCubic(p);
        obj.scale.setScalar(Math.max(0.001, s0 * (1 - e)));
        obj.rotation.z = r0 + 1.2 * e;
      }, () => cell.holder.remove(obj), delay);
    } else {
      this.tween(`xout${obj.id}`, 240, (p) => {
        const up = p < 0.35 ? easeOutQuad(p / 0.35) : 1;
        const s = p < 0.35 ? 1 + 0.3 * up : 1.3 * (1 - easeInQuad((p - 0.35) / 0.65));
        obj.scale.setScalar(Math.max(0.001, s0 * s));
        obj.rotation.z = r0 - 0.9 * easeOutCubic(p);
        obj.position.z = 0.25 * up;
      }, () => cell.holder.remove(obj));
      this.sparksAt(i, 3, ['#bdb8b4', '#ffffff'], 1.4, 0.03);
    }
  }

  /** Katze fällt aufs Feld, prallt mit Squash & Stretch auf, Staub + Funken + Schockwelle. */
  dropCat(i, cat) {
    const ud = cat.userData;
    const land = CAT_LAND_MS / CAT_ANIM_MS;
    let landed = false;
    cat.scale.setScalar(1.8);
    cat.position.z = CAT_Z + 1.6;
    cat.rotation.z = 0.5;
    this.tween(`cat${i}`, CAT_ANIM_MS, (p) => {
      if (p < land) {
        const k = easeInQuad(p / land);
        cat.scale.setScalar(1.8 - 0.8 * k);
        cat.position.z = CAT_Z + 1.6 * (1 - k);
        cat.rotation.z = 0.5 * (1 - k);
        ud.squash.x = -0.08 * (1 - k);
        ud.squash.y = 0.14 * (1 - k);
      } else {
        if (!landed) { landed = true; this.catLanded(i); }
        const q = (p - land) / (1 - land);
        cat.scale.setScalar(1);
        cat.position.z = CAT_Z;
        cat.rotation.z = 0;
        ud.squash.x = 0.3 * damped(q, 1.6, 4.2);
        ud.squash.y = -0.24 * damped(q, 1.6, 4.2);
      }
    }, () => {
      ud.squash.x = 0; ud.squash.y = 0;
      cat.scale.setScalar(1); cat.position.z = CAT_Z; cat.rotation.z = 0;
      if (!landed) this.catLanded(i);
    });
  }

  catLanded(i) {
    const cell = this.cells[i];
    if (!cell) return;
    if (cell.err) {
      // falsche Katze: harter Aufprall ohne Jubel – Rest erledigt flashError()
      this.tween(`press${i}`, 380, (p) => {
        cell.press = (p < 0.15 ? p / 0.15 : 1 - easeOutCubic((p - 0.15) / 0.85)) * 1.8;
      }, () => { cell.press = 0; });
      this.puffAt(i, this.reduced ? 3 : 6, '#ffffff');
      this.cb.onCatLand?.(i, true);
      return;
    }
    this.tween(`press${i}`, 380, (p) => {
      cell.press = (p < 0.15 ? p / 0.15 : 1 - easeOutCubic((p - 0.15) / 0.85)) * 1.8;
    }, () => { cell.press = 0; });
    this.wobble(i, 0.24, 40);
    this.shockwave(i, '#ffffff', 1.5);
    this.puffAt(i, this.reduced ? 3 : 8, '#ffffff');
    const regionColor = this.colors?.[this.regions[i]] || '#ffffff';
    this.sparksAt(i, this.reduced ? 4 : 12, ['#ffd84d', '#ffffff', regionColor, regionColor], 3.4, 0.065);
    this.shake(0.045);
    this.tween('thump', 260, (p) => { this.thump = Math.sin(p * Math.PI); }, () => { this.thump = 0; });
    // Die ganze Farbfläche leuchtet als Welle auf
    const reg = this.regions[i];
    this.cells.forEach((c, k) => {
      if (this.regions[k] !== reg) return;
      const d = this.dist(i, k);
      this.glowCell(k, 0.55, 420, d * 55);
      if (k !== i) this.hopCell(k, 0.08, 260, d * 55);
    });
    this.cb.onCatLand?.(i, false);
  }

  /** Katze springt mit Drehung vom Feld und verpufft. */
  removeCat(i, animate, poof = false) {
    const cell = this.cells[i];
    const obj = cell.cat;
    if (!obj) return;
    cell.cat = null;
    this.tweens.delete(`cat${i}`);
    if (!animate) { cell.holder.remove(obj); return; }
    const s0 = obj.scale.x;
    const ud = obj.userData;
    this.tween(`catout${obj.id}`, 320, (p) => {
      const up = Math.sin(Math.min(1, p * 1.4) * Math.PI * 0.5);
      obj.position.z = CAT_Z + 0.45 * up;
      obj.rotation.z = 1.4 * easeInQuad(p);
      obj.scale.setScalar(Math.max(0.001, s0 * (p < 0.3 ? 1 + 0.15 * (p / 0.3) : 1.15 * (1 - easeInQuad((p - 0.3) / 0.7)))));
      ud.squash.x = 0; ud.squash.y = 0;
    }, () => cell.holder.remove(obj));
    this.puffAt(i, poof ? 12 : 6, poof ? '#c9c4c0' : '#ffffff', poof ? 0.55 : 0.4, 120);
    this.wobble(i, 0.12);
  }

  /** Falsche Katze: nach der Landung Kopfschütteln, rotes Blinken, Wackeln, rote Funken. */
  flashError(i) {
    const cell = this.cells[i];
    if (!cell) return;
    cell.err = true;
    const cat = cell.cat;
    this.tween(`flash${i}`, 820, (p) => {
      cell.flash = Math.abs(Math.sin(p * Math.PI * 2)) * (1 - p * 0.5);
      if (cat) cat.userData.shake = Math.sin(p * Math.PI * 6) * 0.7 * (1 - p);
    }, () => { cell.flash = 0; cell.err = false; if (cat) cat.userData.shake = 0; }, CAT_LAND_MS);
    this.tween(`errfx${i}`, 1, () => {}, () => {
      this.shake(0.12);
      this.sparksAt(i, 10, ['#e5484d', '#ff8a8a', '#ffffff'], 3, 0.06);
      this.wobble(i, 0.3);
    }, CAT_LAND_MS + 20);
  }

  /** Falsche Katze verschwindet in einer Staubwolke (statt normalem Absprung). */
  poofCat(i) {
    const cell = this.cells[i];
    if (!cell || !cell.cat) return;
    cell.state = MARK_EMPTY;
    this.removeCat(i, true, true);
  }

  /** Konflikte: roter, pulsierender Rahmen um die Felder (unabhängig von der Kachelfarbe). */
  setConflicts(list) {
    const next = new Set(list);
    if (next.size === this.conflicts.size && [...next].every((i) => this.conflicts.has(i))) return;
    for (const o of this.conflictObjs) o.parent?.remove(o);
    this.conflictObjs = [];
    this.conflicts = next;
    for (const i of next) {
      const cell = this.cells[i];
      if (!cell) continue;
      const m = new THREE.Mesh(this.conflictGeo, this.conflictMat);
      m.position.z = 0.05;
      m.renderOrder = 2;
      cell.holder.add(m);
      this.conflictObjs.push(m);
    }
    this.invalidate();
  }

  /**
   * Hinweis anzeigen: { focus: [Zellen], target, eliminate: [Zellen], ghost: [Zellen] }
   * ghost = angenommene Katzen (Widerspruch), halbdurchsichtig.
   */
  setHint(h) {
    this.clearHint();
    this.touch();
    this.hint = {
      unitCells: new Set(h.focus || []),
      target: h.target ?? -1,
      elim: new Set(h.eliminate || []),
      objs: [],
      nextSpark: 0,
    };
    if (this.hint.target >= 0) {
      const ring = new THREE.Mesh(this.ringGeo, this.ringMat);
      ring.position.z = 0.03;
      ring.scale.setScalar(0.001);
      this.cells[this.hint.target].holder.add(ring);
      this.hint.objs.push({ cell: this.hint.target, parent: this.cells[this.hint.target].holder, obj: ring, ring: true, born: performance.now() });
      this.sparksAt(this.hint.target, 10, ['#fff3a8', '#ffd84d', '#ffffff'], 2.4, 0.05);
    }
    let k = 0;
    for (const i of this.hint.elim) {
      if (this.cells[i].x) continue;
      const g = this.makeX(this.xHintMat);
      g.castShadow = false;
      g.scale.setScalar(0.001);
      this.cells[i].holder.add(g);
      this.hint.objs.push({ cell: i, parent: this.cells[i].holder, obj: g, born: performance.now() + k * 40 });
      k++;
    }
    (h.ghost || []).forEach((i, q) => {
      const cell = this.cells[i];
      if (!cell || cell.cat) return;
      const ghost = createCat(this.skin);
      ghost.traverse((o) => { if (o.isMesh) { o.material = this.ghostMat; o.castShadow = false; } });
      ghost.position.set(0, CAT_Y, CAT_Z);
      ghost.scale.setScalar(0.001);
      cell.holder.add(ghost);
      this.hint.objs.push({ cell: i, parent: cell.holder, obj: ghost, ghost: true, born: performance.now() + 250 + q * 220 });
    });
    // Fokusfelder springen kurz an
    [...this.hint.unitCells].forEach((i, q) => this.hopCell(i, 0.07, 260, q * 25));
  }

  /** Erledigte Felder aus dem Hinweis nehmen; keep = noch offene Felder. */
  trimHint(keep) {
    const h = this.hint;
    if (!h) return;
    h.objs = h.objs.filter((o) => {
      if (o.ghost || keep.has(o.cell)) return true;
      o.parent.remove(o.obj);
      return false;
    });
    for (const i of [...h.elim]) if (!keep.has(i)) h.elim.delete(i);
    if (h.target >= 0 && !keep.has(h.target)) h.target = -1;
    this.invalidate();
  }

  clearHint() {
    if (!this.hint) return;
    for (const o of this.hint.objs) o.parent.remove(o.obj);
    this.hint = null;
    this.invalidate();
  }

  // ---------------------------------------------------------------------------
  // Sieg
  // ---------------------------------------------------------------------------

  celebrate() {
    this.touch();
    const calm = this.reduced;
    const n = this.n;
    const mid = (n - 1) / 2;
    const half = (n * PITCH) / 2;
    const distC = (cell) => Math.hypot(cell.r - mid, cell.c - mid);
    this.shake(0.06);

    // 1. Alle X ploppen von innen nach außen weg
    this.cells.forEach((cell, i) => {
      if (!cell.x) return;
      cell.state = MARK_EMPTY;
      const obj = cell.x;
      cell.x = null;
      const s0 = obj.scale.x;
      this.tween(`xout${obj.id}`, 260, (p) => {
        obj.scale.setScalar(Math.max(0.001, s0 * (p < 0.3 ? 1 + p : 1.3 * (1 - easeInQuad((p - 0.3) / 0.7)))));
        obj.rotation.z += 0.08;
        obj.position.z = 0.3 * Math.sin(p * Math.PI);
      }, () => cell.holder.remove(obj), distC(cell) * 35);
    });

    // 2. Welle über das ganze Brett: Kacheln hüpfen und leuchten
    this.cells.forEach((cell, i) => {
      const d = distC(cell) * 60 + 180;
      this.hopCell(i, 0.32, 380, d);
      this.glowCell(i, 0.5, 420, d);
    });
    if (calm) return; // ruhige Animationen: nur das Leuchten

    // 3. Katzen springen nacheinander mit Pirouette
    const cats = this.cells.map((c, i) => ({ c, i })).filter((o) => o.c.cat);
    cats.forEach(({ c, i }, k) => {
      const cat = c.cat;
      const ud = cat.userData;
      const delay = 420 + k * 120;
      this.tween(`hopcat${i}`, 900, (p) => {
        const jump = p < 0.55 ? Math.sin((p / 0.55) * Math.PI) : 0;
        cat.position.z = CAT_Z + jump * 0.9;
        cat.scale.setScalar(1 + jump * 0.35);
        cat.rotation.z = p < 0.55 ? Math.PI * 2 * easeInOutCubic(p / 0.55) : 0;
        const q = p >= 0.55 ? (p - 0.55) / 0.45 : 0;
        ud.squash.x = p >= 0.55 ? 0.3 * damped(q, 1.5, 4) : -0.06 * jump;
        ud.squash.y = p >= 0.55 ? -0.24 * damped(q, 1.5, 4) : 0.1 * jump;
      }, () => {
        cat.position.z = CAT_Z; cat.rotation.z = 0; cat.scale.setScalar(1);
        ud.squash.x = 0; ud.squash.y = 0;
      }, delay);
      this.tween(`catfx${i}`, 1, () => {}, () => {
        this.sparksAt(i, 10, ['#ffd84d', '#ffffff', '#ffb3c7'], 3.2, 0.07);
      }, delay + 120);
      this.tween(`catland${i}`, 1, () => {}, () => {
        this.puffAt(i, 5, '#ffffff', 0.4);
        this.shockwave(i, '#fff3a8', 1.2, 380);
      }, delay + 495);
    });

    // 4. Konfetti: zwei Kanonen von unten + Regen von oben
    this.tween('confetti1', 1, () => {}, () => {
      const c = 110;
      this.confettiBurst(-half, -half - 0.4, c, 0.55, 1, 11);
      this.confettiBurst(half, -half - 0.4, c, -0.55, 1, 11);
      this.shake(0.05);
    }, 300);
    this.tween('confetti2', 1, () => {}, () => this.confettiRain(150), 700);
  }

  // ---------------------------------------------------------------------------
  // Animationsschleife
  // ---------------------------------------------------------------------------

  /** Animation unter einem Schlüssel; ersetzt eine laufende – deren Abschluss läuft vorher (keine Geisterobjekte). */
  tween(key, dur, fn, done, delay = 0) {
    const old = this.tweens.get(key);
    if (old) {
      this.tweens.delete(key);
      try { old.done?.(); } catch (e) { this.reportError(e); }
    }
    this.tweens.set(key, { t0: performance.now() + delay, dur, fn, done });
    this.invalidate();
  }

  frame() {
    if (this.paused) { this.stopLoop(); return; }
    const now = performance.now();
    // Leerlauf (Atmen, Pulsieren): gedrosselt
    if (this.mode === 'ambient' && !this.dirty && now - this.lastRender < AMBIENT_FRAME_MS) return;
    this.dirty = false;
    try {
      this.step(now);
    } catch (e) {
      this.reportError(e);
      this.tweens.clear();
    }
  }

  step(now) {
    const dt = Math.min(0.05, (now - this.last) / 1000);
    this.last = now;

    for (const [key, tw] of this.tweens) {
      if (now < tw.t0) continue;
      const p = Math.min(1, (now - tw.t0) / tw.dur);
      try {
        tw.fn(p);
        if (p >= 1) {
          if (this.tweens.get(key) === tw) this.tweens.delete(key);
          tw.done?.();
        }
      } catch (e) {
        this.tweens.delete(key);
        this.reportError(e);
      }
    }

    // Kamerawackeln
    const cam = this.camera;
    if (this.shakeAmp > 0.002) {
      const a = this.shakeAmp;
      cam.position.set(this.camBase.x + rand(-a, a), this.camBase.y + rand(-a, a), this.camBase.z);
      this.shakeAmp *= Math.exp(-dt * 11);
    } else if (this.shakeAmp) {
      this.shakeAmp = 0;
      cam.position.copy(this.camBase);
    }
    this.group.scale.setScalar(1 - this.thump * 0.012);

    const calm = this.reduced;
    const pulse = calm ? 0.5 : 0.5 + 0.5 * Math.sin(now / 260);
    const hint = this.hint;
    const liftK = Math.min(1, dt * 14);
    let liftMoving = false;
    let cats = false;
    for (let i = 0; i < this.cells.length; i++) {
      const cell = this.cells[i];
      const t = cell.tile;
      const hovered = (i === this.hover && !this.drag) || (hint && i === hint.target) ? 1 : 0;
      const dl = hovered - cell.lift;
      if (Math.abs(dl) > 0.002) { cell.lift += dl * liftK; liftMoving = true; } else cell.lift = hovered;
      const sz = 1 - cell.sq;
      const sxy = (1 + cell.sq * 0.5) * cell.introS;
      const z = TILE_H / 2 + cell.introZ + cell.hop + cell.lift * 0.05 - cell.press * 0.06;
      t.position.z = z;
      t.scale.set(sxy, sxy, sz * cell.introS);
      t.rotation.z = cell.rot;
      cell.holder.position.z = z + (TILE_H / 2) * sz * cell.introS;
      cell.holder.scale.set(sxy, sxy, cell.introS);
      cell.holder.rotation.z = cell.rot;

      // Leuchten immer im Farbton der Kachel – „Orange“ bleibt orange
      let k = 0.07 * cell.lift;
      if (hint) {
        if (i === hint.target) k += 0.18 + 0.14 * pulse;
        else if (hint.elim.has(i)) k += 0.1 + 0.08 * pulse;
        else if (hint.unitCells.has(i)) k += 0.1 + 0.08 * pulse;
      }
      if (cell.glow) k += 0.45 * cell.glow;
      const em = t.material.emissive;
      em.copy(t.material.color).multiplyScalar(k);
      if (cell.flash) { em.r += 0.6 * cell.flash; em.g = Math.max(0, em.g - 0.05 * cell.flash); }
      if (cell.cat) { cats = true; animateCat(cell.cat, now, this.dozeK, calm); }
    }
    for (const o of this.conflictObjs) {
      const s = 1 + 0.04 * pulse;
      o.scale.set(s, s, 1);
    }
    this.conflictMat.opacity = 0.7 + 0.3 * pulse;

    if (hint) {
      for (const o of hint.objs) {
        const age = Math.max(0, (now - o.born) / 1000);
        const intro = Math.min(1, age / 0.35);
        if (o.ring) {
          const s = easeOutBack(intro, 2.4) * (1 + pulse * 0.12);
          o.obj.scale.set(s, s, 1);
          if (!calm) o.obj.rotation.z = now / 600;
        } else if (o.ghost) {
          o.obj.scale.setScalar(Math.max(0.001, easeOutBack(intro, 1.8)));
        } else {
          o.obj.scale.setScalar(Math.max(0.001, easeOutBack(intro, 2.4) * (0.85 + pulse * 0.15)));
        }
      }
      if (hint.target >= 0 && now > hint.nextSpark && !calm) {
        hint.nextSpark = now + 320;
        this.sparksAt(hint.target, 2, ['#fff3a8', '#ffd84d'], 1.2, 0.04);
      }
    }

    this.updateLongPress(now);
    this.updateMouse(now);
    if (this.cursorObj) {
      const s = 1 + (calm ? 0 : Math.sin(now / 180) * 0.03);
      this.cursorObj.scale.set(s, s, 1);
    }
    this.puffs.update(dt);
    this.sparks.update(dt);
    this.confetti.update(dt);
    this.fx = this.fx.filter((f) => {
      const alive = f.update(now);
      if (!alive) f.dispose();
      return alive;
    });
    this.renderer.render(this.scene, this.camera);
    this.lastRender = now;

    // Wie geht es weiter? Volle Rate, gedrosselt oder Schleife anhalten.
    const busy = this.tweens.size > 0 || this.fx.length > 0 || this.puffs.live.length > 0 || this.sparks.live.length > 0
      || this.confetti.live.length > 0 || this.shakeAmp > 0 || !!this.mouseRun || !!this.lp || liftMoving || !!this.drag;
    if (cats && !busy && !this.dozing && now - this.lastActive > DOZE_MS) {
      this.dozing = true;
      this.tween('doze', 900, (p) => { this.dozeK = easeInOutCubic(p); }, () => { this.dozeK = 1; });
    }
    const pulsing = !calm && (!!hint || this.conflicts.size > 0 || !!this.cursorObj);
    const breathing = !calm && cats && !this.dozing;
    this.mode = busy || this.tweens.size ? 'busy' : pulsing || breathing ? 'ambient' : 'idle';
    if (this.mode === 'idle' && !this.dirty) this.stopLoop();
  }

  /** Zahl der Objekte je Feld – für Tests (Geisterobjekte erkennen). */
  census() {
    return this.cells.map((c) => ({
      state: c.state,
      cat: !!c.cat,
      x: !!c.x,
      paw: !!c.paw,
      children: c.holder.children.length,
    }));
  }

  dispose() {
    this.stopLoop();
    this.paused = true;
    this.resizeObserver.disconnect();
    this.dprMq?.removeEventListener?.('change', this.dprListener);
    this.renderer.dispose();
  }
}
