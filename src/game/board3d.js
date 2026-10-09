// three.js-Darstellung des Spielfelds: Kacheln, X-Markierungen, Katzen, Hinweise, Konfetti.
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { createCat, animateCat } from './catModel.js';

const PITCH = 1.1;
const TILE = 1.0;
const TILE_H = 0.22;
const TILT = 0.2; // Kameraneigung (rad) – zeigt die Kachelkanten
const MARGIN = 0.12;

import { MARK_EMPTY, MARK_X, MARK_CAT, MARK_AUTO_X } from './marks.js';

const easeOutBack = (t) => {
  const c1 = 1.70158;
  const c3 = c1 + 1;
  return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
};
const easeOutCubic = (t) => 1 - Math.pow(1 - t, 3);

export class Board3D {
  constructor(container, callbacks) {
    this.container = container;
    this.cb = callbacks;
    this.cells = [];
    this.tweens = new Map();
    this.n = 0;
    this.hint = null;
    this.conflicts = new Set();
    this.confetti = null;
    this.hover = -1;
    this.drag = null;

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFShadowMap;
    this.renderer.setClearColor(0x000000, 0);
    container.appendChild(this.renderer.domElement);
    this.canvas = this.renderer.domElement;
    this.canvas.classList.add('board-canvas');

    this.scene = new THREE.Scene();
    this.camera = new THREE.OrthographicCamera(-5, 5, 5, -5, 0.1, 100);

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
    const barGeo = new RoundedBoxGeometry(0.56, 0.1, 0.04, 2, 0.02);
    this.xMat = new THREE.MeshStandardMaterial({ color: '#5a3a33', roughness: 0.6, transparent: true, opacity: 0.8 });
    this.xAutoMat = new THREE.MeshStandardMaterial({ color: '#5a3a33', roughness: 0.6, transparent: true, opacity: 0.45 });
    this.xHintMat = new THREE.MeshStandardMaterial({ color: '#d4343a', emissive: '#7a0d12', roughness: 0.5, transparent: true, opacity: 0.85 });
    this.barGeo = barGeo;
    this.ringGeo = new THREE.TorusGeometry(0.36, 0.045, 12, 48);
    this.ringMat = new THREE.MeshBasicMaterial({ color: '#fff7c2', transparent: true, opacity: 0.95 });
    this.borderMat = new THREE.MeshStandardMaterial({ color: '#4a312b', roughness: 0.8 });

    this.raycaster = new THREE.Raycaster();
    this.plane = new THREE.Plane(new THREE.Vector3(0, 0, 1), -TILE_H);
    this.tmpV = new THREE.Vector2();
    this.tmpP = new THREE.Vector3();

    this.bindInput();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.renderer.setAnimationLoop((t) => this.frame(t));
  }

  // ---------------------------------------------------------------------------
  // Aufbau
  // ---------------------------------------------------------------------------

  setLevel(level, colors, showBorders) {
    this.clearHint();
    this.stopConfetti();
    this.conflicts.clear();
    for (const c of this.cells) {
      c.tile.material.dispose();
    }
    this.group.clear();
    this.tweens.clear();
    this.cells = [];
    const n = level.n;
    this.n = n;
    this.regions = level.regions;
    const half = ((n - 1) / 2) * PITCH;
    for (let i = 0; i < n * n; i++) {
      const r = Math.floor(i / n);
      const c = i % n;
      const mat = new THREE.MeshStandardMaterial({ color: colors[level.regions[i]], roughness: 0.82, metalness: 0 });
      const tile = new THREE.Mesh(this.tileGeo, mat);
      tile.position.set(c * PITCH - half, half - r * PITCH, TILE_H / 2);
      tile.receiveShadow = true;
      tile.castShadow = true;
      this.group.add(tile);
      const holder = new THREE.Group();
      holder.position.set(tile.position.x, tile.position.y, TILE_H);
      this.group.add(holder);
      this.cells.push({ tile, holder, state: MARK_EMPTY, x: null, cat: null, press: 0, flash: 0, baseZ: TILE_H / 2 });
    }
    this.borders = new THREE.Group();
    const gap = PITCH - TILE;
    for (let i = 0; i < n * n; i++) {
      const r = Math.floor(i / n);
      const c = i % n;
      const p = this.cells[i].tile.position;
      if (c < n - 1 && level.regions[i] !== level.regions[i + 1]) {
        const m = new THREE.Mesh(new THREE.BoxGeometry(gap * 0.75, PITCH + gap * 0.4, TILE_H * 1.05), this.borderMat);
        m.position.set(p.x + PITCH / 2, p.y, TILE_H * 0.52);
        this.borders.add(m);
      }
      if (r < n - 1 && level.regions[i] !== level.regions[i + n]) {
        const m = new THREE.Mesh(new THREE.BoxGeometry(PITCH + gap * 0.4, gap * 0.75, TILE_H * 1.05), this.borderMat);
        m.position.set(p.x, p.y - PITCH / 2, TILE_H * 0.52);
        this.borders.add(m);
      }
    }
    this.group.add(this.borders);
    this.setShowBorders(showBorders);

    const ext = (n * PITCH) / 2 + 1;
    const sc = this.sun.shadow.camera;
    sc.left = -ext; sc.right = ext; sc.top = ext; sc.bottom = -ext; sc.near = 0.5; sc.far = 40;
    sc.updateProjectionMatrix();

    // Einblend-Animation der Kacheln
    const start = performance.now();
    this.cells.forEach((cell, i) => {
      const r = Math.floor(i / n);
      const c = i % n;
      cell.tile.scale.setScalar(0.001);
      this.tween(`in${i}`, 380, (p) => cell.tile.scale.setScalar(Math.max(0.001, easeOutBack(p))), null, (r + c) * 22);
    });
    this.levelStart = start;
    this.resize();
  }

  setShowBorders(v) {
    if (this.borders) this.borders.visible = !!v;
  }

  resize() {
    const w = Math.max(10, this.container.clientWidth);
    this.renderer.setSize(w, w, false);
    this.canvas.style.width = `${w}px`;
    this.canvas.style.height = `${w}px`;
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
      if (this.locked) return;
      const cell = this.pick(ev);
      if (cell < 0) return;
      ev.preventDefault();
      try { el.setPointerCapture(ev.pointerId); } catch { /* egal */ }
      this.drag = { start: cell, last: cell, moved: false, id: ev.pointerId };
      this.pressCell(cell);
    });
    el.addEventListener('pointermove', (ev) => {
      const cell = this.pick(ev);
      if (!this.drag) {
        this.hover = ev.pointerType === 'mouse' ? cell : -1;
        return;
      }
      if (ev.pointerId !== this.drag.id || cell < 0 || cell === this.drag.last) return;
      if (!this.drag.moved) {
        this.drag.moved = true;
        this.cb.onDragStart?.(this.drag.start);
      }
      this.drag.last = cell;
      this.pressCell(cell);
      this.cb.onDragEnter?.(cell);
    });
    const end = (ev, cancelled) => {
      if (!this.drag || ev.pointerId !== this.drag.id) return;
      const d = this.drag;
      this.drag = null;
      if (d.moved) this.cb.onDragEnd?.();
      else if (!cancelled) this.cb.onTap?.(d.start);
    };
    el.addEventListener('pointerup', (ev) => end(ev, false));
    el.addEventListener('pointercancel', (ev) => end(ev, true));
    el.addEventListener('pointerleave', () => { this.hover = -1; });
    el.addEventListener('contextmenu', (ev) => ev.preventDefault());
  }

  pressCell(i) {
    const cell = this.cells[i];
    if (!cell) return;
    this.tween(`press${i}`, 260, (p) => {
      cell.press = Math.sin(p * Math.PI) * (1 - p * 0.3);
    }, () => { cell.press = 0; });
  }

  // ---------------------------------------------------------------------------
  // Zustand
  // ---------------------------------------------------------------------------

  /** Gleicht die Darstellung mit den Markierungen ab (mit Animationen). */
  setMarks(marks, animate = true) {
    marks.forEach((m, i) => this.setCell(i, m, animate));
  }

  setCell(i, state, animate = true) {
    const cell = this.cells[i];
    if (!cell || cell.state === state) return;
    const prev = cell.state;
    cell.state = state;
    const isX = state === MARK_X || state === MARK_AUTO_X;
    const wasX = prev === MARK_X || prev === MARK_AUTO_X;

    if (wasX && !isX) this.removeX(i, animate);
    if (prev === MARK_CAT && state !== MARK_CAT) this.removeCat(i, animate);

    if (isX) {
      if (!cell.x) {
        cell.x = this.makeX(state === MARK_AUTO_X ? this.xAutoMat : this.xMat);
        cell.holder.add(cell.x);
        if (animate) {
          const obj = cell.x;
          obj.scale.setScalar(0.001);
          this.tween(`x${i}`, 220, (p) => obj.scale.setScalar(Math.max(0.001, easeOutBack(p))));
        }
      } else {
        for (const b of cell.x.children) b.material = state === MARK_AUTO_X ? this.xAutoMat : this.xMat;
      }
    }
    if (state === MARK_CAT && !cell.cat) {
      const cat = createCat();
      cat.position.z = 0.3;
      cell.cat = cat;
      cell.holder.add(cat);
      if (animate) {
        cat.scale.setScalar(0.001);
        this.tween(`cat${i}`, 420, (p) => {
          cat.scale.setScalar(Math.max(0.001, easeOutBack(p)));
          cat.position.z = 0.3 + Math.sin(p * Math.PI) * 0.35;
        });
      }
    }
  }

  makeX(mat) {
    const g = new THREE.Group();
    for (const rot of [Math.PI / 4, -Math.PI / 4]) {
      const b = new THREE.Mesh(this.barGeo, mat);
      b.rotation.z = rot;
      b.position.z = 0.025;
      g.add(b);
    }
    return g;
  }

  removeX(i, animate) {
    const cell = this.cells[i];
    const obj = cell.x;
    if (!obj) return;
    cell.x = null;
    this.tweens.delete(`x${i}`);
    if (!animate) { cell.holder.remove(obj); return; }
    this.tween(`xout${i}`, 150, (p) => obj.scale.setScalar(Math.max(0.001, 1 - p)), () => cell.holder.remove(obj));
  }

  removeCat(i, animate) {
    const cell = this.cells[i];
    const obj = cell.cat;
    if (!obj) return;
    cell.cat = null;
    this.tweens.delete(`cat${i}`);
    if (!animate) { cell.holder.remove(obj); return; }
    this.tween(`catout${i}`, 200, (p) => {
      obj.scale.setScalar(Math.max(0.001, 1 - easeOutCubic(p)));
      obj.position.z = 0.3 + p * 0.3;
    }, () => cell.holder.remove(obj));
  }

  /** Falsche Katze: rot aufblitzen + schütteln. */
  flashError(i) {
    const cell = this.cells[i];
    if (!cell) return;
    const cat = cell.cat;
    this.tween(`flash${i}`, 700, (p) => {
      cell.flash = Math.sin(p * Math.PI);
      if (cat) cat.position.x = Math.sin(p * Math.PI * 8) * 0.08 * (1 - p);
    }, () => { cell.flash = 0; if (cat) cat.position.x = 0; });
  }

  setConflicts(list) {
    this.conflicts = new Set(list);
  }

  /** Hinweis anzeigen: { units: [Zellen], target, eliminate: [Zellen], wrong: [Zellen] } */
  setHint(h) {
    this.clearHint();
    this.hint = { unitCells: new Set(h.focus || []), target: h.target ?? -1, elim: new Set(h.eliminate || []), objs: [] };
    if (this.hint.target >= 0) {
      const ring = new THREE.Mesh(this.ringGeo, this.ringMat);
      ring.position.z = 0.03;
      this.cells[this.hint.target].holder.add(ring);
      this.hint.objs.push({ parent: this.cells[this.hint.target].holder, obj: ring, ring: true });
    }
    for (const i of this.hint.elim) {
      if (this.cells[i].x) continue;
      const g = this.makeX(this.xHintMat);
      this.cells[i].holder.add(g);
      this.hint.objs.push({ parent: this.cells[i].holder, obj: g });
    }
  }

  clearHint() {
    if (!this.hint) return;
    for (const o of this.hint.objs) o.parent.remove(o.obj);
    this.hint = null;
  }

  // ---------------------------------------------------------------------------
  // Effekte
  // ---------------------------------------------------------------------------

  celebrate() {
    // Katzen hüpfen nacheinander
    const cats = this.cells.map((c, i) => ({ c, i })).filter((o) => o.c.cat);
    cats.forEach(({ c, i }, k) => {
      const cat = c.cat;
      this.tween(`hop${i}`, 1600, (p) => {
        const local = (p * 3) % 1;
        cat.position.z = 0.3 + Math.sin(local * Math.PI) * 0.5 * (1 - p * 0.5);
        cat.rotation.z = Math.sin(p * Math.PI * 6) * 0.15 * (1 - p);
      }, () => { cat.position.z = 0.3; cat.rotation.z = 0; }, k * 90);
    });
    this.startConfetti();
  }

  startConfetti() {
    this.stopConfetti();
    const count = 220;
    const geo = new THREE.PlaneGeometry(0.16, 0.09);
    const mat = new THREE.MeshBasicMaterial({ side: THREE.DoubleSide, transparent: true });
    const mesh = new THREE.InstancedMesh(geo, mat, count);
    const colors = ['#9580d0', '#b6d99c', '#f2ad73', '#ddbd5e', '#f0b7cf', '#68b5b0', '#80b9e2', '#c97674', '#ffffff'];
    const half = (this.n * PITCH) / 2;
    const parts = [];
    const color = new THREE.Color();
    for (let k = 0; k < count; k++) {
      color.set(colors[k % colors.length]);
      mesh.setColorAt(k, color);
      parts.push({
        x: (Math.random() - 0.5) * half * 2.2,
        y: half + 0.5 + Math.random() * half * 1.5,
        z: 1.5 + Math.random() * 2,
        vx: (Math.random() - 0.5) * 1.5,
        vy: -(1.5 + Math.random() * 2.5),
        rx: Math.random() * 6, ry: Math.random() * 6, rz: Math.random() * 6,
        sx: (Math.random() - 0.5) * 12, sy: (Math.random() - 0.5) * 12,
        sway: Math.random() * Math.PI * 2,
      });
    }
    this.scene.add(mesh);
    this.confetti = { mesh, parts, geo, mat, t0: performance.now(), last: performance.now(), floor: -half - 1 };
  }

  stopConfetti() {
    if (!this.confetti) return;
    this.scene.remove(this.confetti.mesh);
    this.confetti.geo.dispose();
    this.confetti.mat.dispose();
    this.confetti.mesh.dispose();
    this.confetti = null;
  }

  updateConfetti(now) {
    const cf = this.confetti;
    if (!cf) return;
    const dt = Math.min(0.05, (now - cf.last) / 1000);
    cf.last = now;
    const age = (now - cf.t0) / 1000;
    const m = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const e = new THREE.Euler();
    const pos = new THREE.Vector3();
    const one = new THREE.Vector3(1, 1, 1);
    cf.parts.forEach((p, k) => {
      p.vy = Math.max(p.vy - 2.5 * dt, -3.2);
      p.x += (p.vx + Math.sin(age * 3 + p.sway) * 0.8) * dt;
      p.y += p.vy * dt;
      p.rx += p.sx * dt; p.ry += p.sy * dt;
      e.set(p.rx, p.ry, p.rz);
      q.setFromEuler(e);
      pos.set(p.x, p.y, p.z);
      m.compose(pos, q, one);
      cf.mesh.setMatrixAt(k, m);
    });
    cf.mesh.instanceMatrix.needsUpdate = true;
    cf.mat.opacity = age > 3.5 ? Math.max(0, 1 - (age - 3.5)) : 1;
    if (age > 4.5) this.stopConfetti();
  }

  // ---------------------------------------------------------------------------
  // Animationsschleife
  // ---------------------------------------------------------------------------

  tween(key, dur, fn, done, delay = 0) {
    this.tweens.set(key, { t0: performance.now() + delay, dur, fn, done });
  }

  frame() {
    const now = performance.now();
    for (const [key, tw] of this.tweens) {
      if (now < tw.t0) continue;
      const p = Math.min(1, (now - tw.t0) / tw.dur);
      tw.fn(p);
      if (p >= 1) {
        this.tweens.delete(key);
        tw.done?.();
      }
    }
    const pulse = 0.5 + 0.5 * Math.sin(now / 260);
    const hint = this.hint;
    for (let i = 0; i < this.cells.length; i++) {
      const cell = this.cells[i];
      const t = cell.tile;
      t.position.z = cell.baseZ - cell.press * 0.06;
      cell.holder.position.z = TILE_H - cell.press * 0.06;
      let er = 0;
      let eg = 0;
      let eb = 0;
      if (i === this.hover && !this.drag) { er += 0.06; eg += 0.06; eb += 0.06; }
      if (hint) {
        if (i === hint.target) { er += 0.22 + 0.2 * pulse; eg += 0.2 + 0.18 * pulse; eb += 0.05; }
        else if (hint.elim.has(i)) { er += 0.12 + 0.12 * pulse; eg += 0.02; eb += 0.02; }
        else if (hint.unitCells.has(i)) { er += 0.1 + 0.1 * pulse; eg += 0.1 + 0.1 * pulse; eb += 0.08 + 0.08 * pulse; }
      }
      if (this.conflicts.has(i)) { er += 0.25 + 0.25 * pulse; }
      if (cell.flash) { er += 0.55 * cell.flash; }
      t.material.emissive.setRGB(er, eg, eb);
      if (cell.cat) animateCat(cell.cat, now);
    }
    if (hint) {
      for (const o of hint.objs) {
        if (o.ring) {
          const s = 1 + pulse * 0.12;
          o.obj.scale.set(s, s, 1);
        } else {
          o.obj.scale.setScalar(0.85 + pulse * 0.15);
        }
      }
    }
    this.updateConfetti(now);
    this.renderer.render(this.scene, this.camera);
  }

  dispose() {
    this.renderer.setAnimationLoop(null);
    this.resizeObserver.disconnect();
    this.renderer.dispose();
  }
}
