// VU 확장 결과 CSV로 docs/images/vu-scaling-{light,dark}.svg를 생성한다.
// 실행: node docs/images/render-vu-scaling.mjs   (외부 의존성 없음, Node 18+)

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const csvPath = join(here, '../../k6/results/r0816vuscale1/vu-scaling-results.csv');

const STRATEGIES = [
    ['direct', 'DIRECT', '기준선'],
    ['jvm-lock', 'JVM_LOCK', '애플리케이션'],
    ['pessimistic', 'PESSIMISTIC', 'DB'],
    ['optimistic', 'OPTIMISTIC', 'DB'],
    ['conditional', 'CONDITIONAL', 'DB'],
    ['redis-lock', 'REDIS_LOCK', 'Redis'],
    ['redis-decr', 'REDIS_DECR', 'Redis'],
    ['redis-lua', 'REDIS_LUA', 'Redis'],
];
const VUS = [10, 50, 100, 200];

const THEMES = {
    light: {
        surface: '#fcfcfb', border: 'rgba(11,11,11,0.10)',
        ink: '#0b0b0b', secondary: '#52514e', muted: '#898781',
        grid: '#e1e0d9', axis: '#c3c2b7',
        rps: '#2a78d6', p95: '#eb6834',
    },
    dark: {
        surface: '#1a1a19', border: 'rgba(255,255,255,0.10)',
        ink: '#ffffff', secondary: '#c3c2b7', muted: '#898781',
        grid: '#2c2c2a', axis: '#383835',
        rps: '#3987e5', p95: '#d95926',
    },
};

function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = '';
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (quoted) {
            if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
            else if (c === '"') { quoted = false; }
            else { field += c; }
        } else if (c === '"') { quoted = true; }
        else if (c === ',') { row.push(field); field = ''; }
        else if (c === '\n' || c === '\r') {
            if (c === '\r' && text[i + 1] === '\n') i++;
            row.push(field); rows.push(row); row = []; field = '';
        } else { field += c; }
    }
    if (field || row.length) { row.push(field); rows.push(row); }
    const [header, ...body] = rows.filter((r) => r.length > 1);
    return body.map((r) => Object.fromEntries(header.map((h, i) => [h.replace(/^﻿/, ''), r[i]])));
}

function loadSeries() {
    const records = parseCsv(readFileSync(csvPath, 'utf8'));
    return STRATEGIES.map(([key, label, family]) => {
        const byVu = new Map(records
            .filter((r) => r.Strategy === key)
            .map((r) => [Number(r.Vus), r]));
        const base = byVu.get(10);
        const points = VUS.map((vu) => {
            const r = byVu.get(vu);
            if (!r) throw new Error(`missing ${key} VU ${vu}`);
            return {
                vu,
                rps: Number(r.RequestsPerSecond) / Number(base.RequestsPerSecond),
                p95: Number(r.P95Ms) / Number(base.P95Ms),
            };
        });
        return { label, family, points };
    });
}

function render(series, t) {
    const W = 880;
    const pad = 16;
    const gapX = 24;
    const cols = 4;
    const panelW = (W - pad * 2 - gapX * (cols - 1)) / cols;
    const gutterL = 30;
    const gutterR = 44;
    const plotW = panelW - gutterL - gutterR;
    const plotH = 110;
    const panelH = 28 + plotH + 24;
    const top = 100;
    const gapY = 20;
    const H = top + panelH * 2 + gapY + pad;
    const yMax = 20;
    const font = `system-ui, -apple-system, 'Segoe UI', 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif`;

    const x = (vu) => (vu / 200) * plotW;
    const y = (v) => plotH - (v / yMax) * plotH;
    const out = [];

    out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-labelledby="t d" font-family="${font}">`);
    out.push(`<title id="t">VU를 20배 늘려도 처리량은 거의 그대로, p95 지연만 8~19배 증가</title>`);
    out.push(`<desc id="d">Duplicate user 시나리오에서 REDIS_WATCH를 제외한 8개 전략의 req/s와 p95를 VU 10 대비 배수로 표시. ${series
        .map((s) => `${s.label}: req/s ×${s.points[3].rps.toFixed(2)}, p95 ×${s.points[3].p95.toFixed(1)} (VU 200)`)
        .join('; ')}</desc>`);
    out.push(`<rect x="0.5" y="0.5" width="${W - 1}" height="${H - 1}" rx="10" fill="${t.surface}" stroke="${t.border}"/>`);

    out.push(`<text x="${pad + 4}" y="34" font-size="16" font-weight="600" fill="${t.ink}">VU를 20배 늘려도 처리량은 거의 그대로, p95 지연만 8~19배</text>`);
    out.push(`<text x="${pad + 4}" y="54" font-size="12" fill="${t.secondary}">Duplicate user 시나리오 · REDIS_WATCH 제외 · VU 10 값을 1로 둔 배수 · 폐쇄형 부하(shared-iterations)</text>`);

    const legendY = 78;
    const legend = [
        [t.rps, '처리량 (req/s)'],
        [t.p95, 'p95 지연'],
    ];
    let lx = pad + 4;
    for (const [color, text] of legend) {
        out.push(`<line x1="${lx}" y1="${legendY}" x2="${lx + 20}" y2="${legendY}" stroke="${color}" stroke-width="2" stroke-linecap="round"/>`);
        out.push(`<circle cx="${lx + 10}" cy="${legendY}" r="4" fill="${color}" stroke="${t.surface}" stroke-width="2"/>`);
        out.push(`<text x="${lx + 28}" y="${legendY + 4}" font-size="12" fill="${t.ink}">${text}</text>`);
        lx += 28 + text.length * 8 + 28;
    }

    series.forEach((s, i) => {
        const col = i % cols;
        const row = Math.floor(i / cols);
        const px = pad + col * (panelW + gapX);
        const py = top + row * (panelH + gapY);
        const ox = px + gutterL;
        const oy = py + 28;

        out.push(`<g>`);
        out.push(`<text x="${px}" y="${py + 12}" font-size="13" font-weight="600" fill="${t.ink}">${s.label}</text>`);
        out.push(`<text x="${px + panelW}" y="${py + 12}" font-size="11" text-anchor="end" fill="${t.muted}">${s.family}</text>`);

        for (const v of [5, 10, 15, 20]) {
            out.push(`<line x1="${ox}" y1="${oy + y(v)}" x2="${ox + plotW}" y2="${oy + y(v)}" stroke="${t.grid}" stroke-width="1"/>`);
        }
        for (const v of [0, 10, 20]) {
            out.push(`<text x="${ox - 6}" y="${oy + y(v) + 4}" font-size="10" text-anchor="end" fill="${t.muted}">${v === 0 ? '0' : `${v}×`}</text>`);
        }
        out.push(`<line x1="${ox}" y1="${oy + plotH}" x2="${ox + plotW}" y2="${oy + plotH}" stroke="${t.axis}" stroke-width="1"/>`);
        for (const vu of VUS) {
            out.push(`<text x="${ox + x(vu)}" y="${oy + plotH + 16}" font-size="10" text-anchor="middle" fill="${t.muted}">${vu}</text>`);
        }
        out.push(`<text x="${ox - 6}" y="${oy + plotH + 16}" font-size="10" text-anchor="end" fill="${t.muted}">VU</text>`);

        for (const [key, color, digits] of [['p95', t.p95, 1], ['rps', t.rps, 2]]) {
            const d = s.points.map((p, k) => `${k ? 'L' : 'M'}${(ox + x(p.vu)).toFixed(1)},${(oy + y(p[key])).toFixed(1)}`).join(' ');
            out.push(`<path d="${d}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`);
            for (const p of s.points) {
                out.push(`<circle cx="${(ox + x(p.vu)).toFixed(1)}" cy="${(oy + y(p[key])).toFixed(1)}" r="4" fill="${color}" stroke="${t.surface}" stroke-width="2"/>`);
            }
            const last = s.points[s.points.length - 1];
            out.push(`<text x="${ox + plotW + 8}" y="${(oy + y(last[key]) + 4).toFixed(1)}" font-size="11" fill="${t.ink}">×${last[key].toFixed(digits)}</text>`);
        }
        out.push(`</g>`);
    });

    out.push(`</svg>`);
    return out.join('\n') + '\n';
}

const series = loadSeries();
for (const [name, theme] of Object.entries(THEMES)) {
    const file = join(here, `vu-scaling-${name}.svg`);
    writeFileSync(file, render(series, theme));
    console.log(`wrote ${file}`);
}
for (const s of series) {
    const last = s.points[3];
    console.log(`${s.label.padEnd(12)} rps x${last.rps.toFixed(2)}  p95 x${last.p95.toFixed(2)}`);
}
