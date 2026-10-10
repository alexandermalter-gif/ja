// Ein kleiner 3D-Katzenkopf (Smoking-Katze) aus Grundkörpern – Gesicht zeigt in +Z (zur Kamera).
import * as THREE from 'three';

let shared = null;

function getShared() {
  if (shared) return shared;
  const mat = (color, extra = {}) => new THREE.MeshStandardMaterial({ color, roughness: 0.55, metalness: 0, ...extra });
  shared = {
    geo: {
      sphere: new THREE.SphereGeometry(1, 32, 20),
      smallSphere: new THREE.SphereGeometry(1, 16, 12),
      cone: new THREE.ConeGeometry(1, 1, 20, 1),
      whisker: new THREE.CylinderGeometry(1, 1, 1, 6),
    },
    mat: {
      fur: mat('#2c2b31', { roughness: 0.75 }),
      white: mat('#fbf8f4', { roughness: 0.7 }),
      ear: mat('#f2a4b5', { roughness: 0.8 }),
      eye: mat('#ffffff', { roughness: 0.25 }),
      pupil: mat('#141418', { roughness: 0.2 }),
      nose: mat('#f08aa0', { roughness: 0.4 }),
      mouth: mat('#b8435a', { roughness: 0.6 }),
      whisker: mat('#e9e3dc', { roughness: 0.9 }),
      blush: new THREE.MeshBasicMaterial({ color: '#ff9fb0', transparent: true, opacity: 0.45, depthWrite: false }),
    },
  };
  return shared;
}

function part(geo, mat, pos, scale, rot) {
  const m = new THREE.Mesh(geo, mat);
  m.position.set(...pos);
  m.scale.set(...scale);
  if (rot) m.rotation.set(...rot);
  m.castShadow = true;
  return m;
}

/** Erzeugt einen Katzenkopf (Breite ≈ 0.8 Einheiten). */
export function createCat() {
  const { geo, mat } = getShared();
  const root = new THREE.Group();
  const head = new THREE.Group();
  root.add(head);

  // Kopf (Ellipsoid mit Halbachsen 0.4 / 0.35 / 0.3)
  head.add(part(geo.sphere, mat.fur, [0, 0, 0], [0.4, 0.35, 0.3]));
  // Ohren – je eine Gruppe mit Drehpunkt am Ohransatz (zum Zucken)
  const ears = [];
  for (const s of [-1, 1]) {
    const ear = new THREE.Group();
    ear.position.set(0.18 * s, 0.195, 0);
    ear.rotation.z = -0.45 * s;
    ear.userData.base = ear.rotation.z;
    ear.add(part(geo.cone, mat.fur, [0, 0.15, -0.02], [0.15, 0.3, 0.1]));
    ear.add(part(geo.cone, mat.ear, [0, 0.136, 0.05], [0.085, 0.2, 0.05]));
    head.add(ear);
    ears.push(ear);
  }
  // Weiße Blesse und Schnauze
  head.add(part(geo.sphere, mat.white, [0, 0.07, 0.24], [0.06, 0.15, 0.07]));
  head.add(part(geo.sphere, mat.white, [0, -0.12, 0.13], [0.24, 0.19, 0.19]));
  // Augen
  const eyes = [];
  const pupils = [];
  for (const s of [-1, 1]) {
    const eye = new THREE.Group();
    eye.position.set(0.15 * s, 0.06, 0.255);
    eye.add(part(geo.smallSphere, mat.eye, [0, 0, 0], [0.09, 0.1, 0.05]));
    const pupil = new THREE.Group();
    pupil.position.set(0.012 * s, -0.008, 0);
    pupil.add(part(geo.smallSphere, mat.pupil, [0, 0, 0.03], [0.056, 0.068, 0.03]));
    pupil.add(part(geo.smallSphere, mat.eye, [0.018 * s, 0.038, 0.056], [0.018, 0.018, 0.012]));
    eye.add(pupil);
    head.add(eye);
    eyes.push(eye);
    pupils.push(pupil);
    // Bäckchen
    const blush = new THREE.Mesh(geo.smallSphere, mat.blush);
    blush.position.set(0.25 * s, -0.09, 0.225);
    blush.scale.set(0.06, 0.035, 0.02);
    head.add(blush);
  }
  // Nase und Mund
  head.add(part(geo.smallSphere, mat.nose, [0, -0.055, 0.305], [0.042, 0.03, 0.025]));
  head.add(part(geo.smallSphere, mat.mouth, [0, -0.14, 0.312], [0.04, 0.03, 0.015]));
  // Schnurrhaare
  for (const s of [-1, 1]) {
    for (let i = 0; i < 3; i++) {
      const w = part(geo.whisker, mat.whisker, [0.33 * s, -0.08 - i * 0.04, 0.24], [0.006, 0.22, 0.006], [0, 0, (Math.PI / 2) * s + (i - 1) * 0.16 * s]);
      w.castShadow = false;
      head.add(w);
    }
  }

  const now = performance.now();
  root.userData = {
    head,
    eyes,
    pupils,
    ears,
    blinkAt: now + 1500 + Math.random() * 4000,
    twitchAt: now + 2500 + Math.random() * 6000,
    twitchEar: 0,
    lookAt: now + 2000 + Math.random() * 4000,
    look: { x: 0, y: 0, tx: 0, ty: 0 },
    phase: Math.random() * Math.PI * 2,
    // Squash & Stretch von außen (Landung, Freude): sx/sy relativ zu 1
    squash: { x: 0, y: 0 },
    // „Nein“-Kopfschütteln (Fehler)
    shake: 0,
  };
  return root;
}

/** Leerlauf-Animation: Atmen, Blinzeln, Ohrenzucken, Umschauen – plus Squash & Stretch. */
export function animateCat(cat, now) {
  const ud = cat.userData;
  const t = now / 1000 + ud.phase;
  const sq = ud.squash;
  ud.head.scale.set(
    (1 + Math.sin(t * 2.2) * 0.012) * (1 + sq.x),
    (1 + Math.sin(t * 2.2 + 0.6) * 0.02) * (1 + sq.y),
    1,
  );
  ud.head.rotation.z = Math.sin(t * 0.9) * 0.05;
  ud.head.rotation.y = ud.shake;

  // Blinzeln
  let open = 1;
  const dt = now - ud.blinkAt;
  if (dt > 0) {
    if (dt < 140) open = Math.abs(1 - dt / 70) * 0.9 + 0.1;
    else ud.blinkAt = now + 2200 + Math.random() * 5000;
  }
  for (const e of ud.eyes) e.scale.y = open;

  // Ohrenzucken
  const tw = now - ud.twitchAt;
  ud.ears.forEach((ear, k) => {
    let off = 0;
    if (tw > 0 && tw < 380 && k === ud.twitchEar) off = Math.sin((tw / 380) * Math.PI * 3) * 0.32 * (1 - tw / 380);
    ear.rotation.z = ear.userData.base + off * (k === 0 ? 1 : -1);
  });
  if (tw >= 380) {
    ud.twitchAt = now + 3000 + Math.random() * 7000;
    ud.twitchEar = Math.random() < 0.5 ? 0 : 1;
  }

  // Umschauen
  const lk = ud.look;
  if (now > ud.lookAt) {
    const centre = Math.random() < 0.4;
    lk.tx = centre ? 0 : (Math.random() - 0.5) * 0.05;
    lk.ty = centre ? 0 : (Math.random() - 0.5) * 0.03;
    ud.lookAt = now + 1200 + Math.random() * 3500;
  }
  lk.x += (lk.tx - lk.x) * 0.15;
  lk.y += (lk.ty - lk.y) * 0.15;
  ud.pupils.forEach((p, k) => {
    p.position.x = (k === 0 ? -0.012 : 0.012) + lk.x;
    p.position.y = -0.008 + lk.y;
  });
}
