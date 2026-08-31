// Forge web client.
//
// The server pushes coalesced state snapshots on a WebSocket; card objects arrive as a
// diff and are patched into a local map. Everything below renders from that map, so a
// board update never rebuilds more of the DOM than it has to.

const PHASES = [
  'UNTAP', 'UPKEEP', 'DRAW', 'MAIN1', 'COMBAT_BEGIN', 'COMBAT_DECLARE_ATTACKERS',
  'COMBAT_DECLARE_BLOCKERS', 'COMBAT_FIRST_STRIKE_DAMAGE', 'COMBAT_DAMAGE',
  'COMBAT_END', 'MAIN2', 'END_OF_TURN', 'CLEANUP',
];

const state = {
  cards: new Map(),
  players: [],
  me: null,
  turn: 0,
  phase: '',
  phaseId: '',
  turnPlayer: null,
  stack: [],
  combat: {},
  log: [],
  prompt: null,
  gameOver: false,
};

let socket = null;
let reconnectDelay = 500;
let started = false;

const $ = (id) => document.getElementById(id);

// --------------------------------------------------------------------- socket

function connect() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  socket = new WebSocket(`${protocol}//${location.host}/ws`);

  socket.onopen = () => {
    reconnectDelay = 500;
    $('connection').hidden = true;
  };
  socket.onclose = () => {
    $('connection').hidden = false;
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 8000);
  };
  socket.onmessage = (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    handle(msg);
  };
}

function send(msg) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}

const action = (name, extra = {}) => send({ t: 'action', action: name, ...extra });

function handle(msg) {
  switch (msg.t) {
    case 'state': applyState(msg); break;
    case 'ask': openDialog(msg); break;
    case 'toast': toast(msg.message || msg.title, msg.error); break;
    case 'flash': flashPrompt(); break;
    case 'alert': flashPrompt(); break;
    case 'gameOver': break;
    default: break;
  }
}

// ---------------------------------------------------------------------- state

function applyState(msg) {
  if (msg.full) {
    state.cards.clear();
  }
  for (const card of msg.cards || []) {
    state.cards.set(card.id, card);
  }
  for (const id of msg.removed || []) {
    state.cards.delete(id);
  }

  state.players = msg.players || [];
  state.me = msg.me ?? null;
  state.turn = msg.turn ?? 0;
  state.phase = msg.phase || '';
  state.phaseId = msg.phaseId || '';
  state.turnPlayer = msg.turnPlayer ?? null;
  state.stack = msg.stack || [];
  state.combat = msg.combat || {};
  state.log = msg.log || [];
  state.prompt = msg.prompt || null;
  state.gameOver = !!msg.gameOver;
  state.winner = msg.winner;

  if (!started && state.players.length) {
    started = true;
    $('setup').hidden = true;
    $('table').hidden = false;
  }
  render();
}

const me = () => state.players.find((p) => p.id === state.me) || state.players.find((p) => p.local);
const opponents = () => state.players.filter((p) => p !== me());

// --------------------------------------------------------------------- render

// Coalesce several socket messages into one paint. A timer rather than
// requestAnimationFrame: rAF is suspended in a background tab, which would leave the
// board stale, and the server already throttles pushes to ~20/s.
let renderQueued = false;
function render() {
  if (renderQueued) return;
  renderQueued = true;
  setTimeout(() => {
    renderQueued = false;
    draw();
  }, 0);
}

function draw() {
  const mine = me();
  const them = opponents()[0];

  $('turn-number').textContent = `Turn ${state.turn}`;
  $('phase-name').textContent = state.phase;
  drawPhaseTrack();

  if (them) drawPlayerBar($('player-bar-opponent'), them);
  if (mine) drawPlayerBar($('player-bar-me'), mine);

  drawBattlefield($('bf-opponent'), them);
  drawBattlefield($('bf-me'), mine);
  drawHand(mine);
  drawStack();
  drawCombat();
  drawPrompt();
  drawManaPool(mine);
  drawLog();
}

function drawPhaseTrack() {
  const track = $('phase-track');
  const current = PHASES.indexOf(state.phaseId);
  if (track.childElementCount !== PHASES.length) {
    track.replaceChildren(...PHASES.map((p) => {
      const li = document.createElement('li');
      li.title = p.replace(/_/g, ' ').toLowerCase();
      return li;
    }));
  }
  [...track.children].forEach((li, i) => {
    li.className = i === current ? 'now' : (current >= 0 && i < current ? 'done' : '');
  });
}

function drawPlayerBar(el, player) {
  if (!player) return;
  el.className = 'player-bar'
    + (player.priority ? ' priority' : '')
    + (player.hl ? ' targetable' : '');
  el.onclick = () => action('player', { id: player.id });

  const zones = [
    ['Hand', player.hand, null],
    ['Library', player.library, null],
    ['Graveyard', (player.gy || []).length, () => showZone(`${player.name} — graveyard`, player.gy)],
    ['Exile', (player.exile || []).length, () => showZone(`${player.name} — exile`, player.exile)],
  ];

  el.replaceChildren();
  const name = document.createElement('span');
  name.className = 'name';
  name.textContent = player.name + (player.ai ? ' (AI)' : '');
  const life = document.createElement('span');
  life.className = 'life' + (player.life <= 5 ? ' low' : '');
  life.textContent = player.life;
  const zoneWrap = document.createElement('div');
  zoneWrap.className = 'zones';
  for (const [label, count, onClick] of zones) {
    const chip = document.createElement('div');
    chip.className = 'zone-chip' + (onClick && count ? ' clickable' : '');
    chip.innerHTML = `${label} <b>${count ?? 0}</b>`;
    if (onClick && count) {
      chip.onclick = (e) => { e.stopPropagation(); onClick(); };
    }
    zoneWrap.append(chip);
  }
  el.append(life, name, zoneWrap);

  for (const [type, n] of Object.entries(player.counters || {})) {
    const badge = document.createElement('span');
    badge.className = 'badge counter';
    badge.textContent = `${type} ${n}`;
    el.append(badge);
  }
}

function drawBattlefield(el, player) {
  if (!player) {
    el.replaceChildren();
    return;
  }
  // Lands to the back, creatures to the front, so the board reads at a glance.
  const ids = (player.bf || []).slice().sort((a, b) => rank(a) - rank(b));
  syncCards(el, ids);
}

function rank(id) {
  const card = state.cards.get(id);
  if (!card) return 9;
  const type = card.type || '';
  if (type.includes('Creature')) return 0;
  if (type.includes('Planeswalker') || type.includes('Battle')) return 1;
  if (type.includes('Land')) return 4;
  return 2;
}

function drawHand(player) {
  syncCards($('hand'), (player && player.handCards) || []);
}

// Reuses existing card nodes where possible; only re-renders a card whose data changed.
function syncCards(container, ids) {
  const existing = new Map();
  for (const node of container.children) {
    existing.set(Number(node.dataset.id), node);
  }
  const nodes = [];
  for (const id of ids) {
    const card = state.cards.get(id);
    if (!card) continue;
    let node = existing.get(id);
    const signature = JSON.stringify(card);
    if (node && node.dataset.sig === signature) {
      existing.delete(id);
    } else {
      node = renderCard(card, node);
      node.dataset.sig = signature;
      existing.delete(id);
    }
    nodes.push(node);
  }
  for (const stale of existing.values()) {
    stale.remove();
  }
  // replaceChildren with the ordered list is one layout pass and keeps node identity,
  // so CSS transitions on the surviving cards aren't restarted.
  container.replaceChildren(...nodes);
}

function renderCard(card, reuse) {
  const el = reuse || document.createElement('div');
  el.dataset.id = card.id;
  // Cards are the primary control surface, so give them a real role and keyboard focus
  // rather than leaving them as click-only divs.
  el.setAttribute('role', 'button');
  el.tabIndex = card.sel || card.weak ? 0 : -1;
  el.setAttribute('aria-label', ariaLabel(card));
  el.className = 'card'
    + (card.tapped ? ' tapped' : '')
    + (card.sick ? ' sick' : '')
    + (card.phased ? ' phased' : '')
    + (card.sel ? ' selectable' : '')
    + (card.hl ? ' highlighted' : '')
    + (card.weak ? ' weak' : '')
    + (card.attacking ? ' attacking' : '')
    + (card.blocking ? ' blocking' : '')
    + (card.hidden ? ' hidden-card' : '');

  el.replaceChildren();

  if (!card.hidden) {
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.decoding = 'async';
    img.alt = card.name || '';
    img.src = imageUrl(card);
    img.onerror = () => { img.remove(); el.prepend(facelet(card)); };
    el.append(img);
  }

  const badges = document.createElement('div');
  badges.className = 'badges';
  for (const [type, n] of Object.entries(card.counters || {})) {
    const badge = document.createElement('span');
    badge.className = 'badge counter';
    badge.textContent = shortCounter(type, n);
    badges.append(badge);
  }
  if (card.damage) {
    const badge = document.createElement('span');
    badge.className = 'badge damage';
    badge.textContent = `-${card.damage}`;
    badges.append(badge);
  }
  if (badges.childElementCount) el.append(badges);

  if (card.power !== undefined) {
    const pt = document.createElement('span');
    pt.className = 'pt' + (card.damage ? ' hurt' : '');
    pt.textContent = `${card.power}/${card.toughness}`;
    el.append(pt);
  } else if (card.loyalty) {
    const pt = document.createElement('span');
    pt.className = 'pt';
    pt.textContent = card.loyalty;
    el.append(pt);
  }

  el.onclick = () => action('card', { id: card.id });
  el.onkeydown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      e.stopPropagation();
      action('card', { id: card.id });
    }
  };
  el.onmouseenter = () => inspect(card);
  el.onmouseleave = hideInspector;
  el.onfocus = () => inspect(card);
  el.onblur = hideInspector;
  return el;
}

function ariaLabel(card) {
  if (card.hidden) return 'Face-down card';
  const parts = [card.name];
  if (card.power !== undefined) parts.push(`${card.power}/${card.toughness}`);
  if (card.tapped) parts.push('tapped');
  if (card.attacking) parts.push('attacking');
  if (card.blocking) parts.push('blocking');
  if (card.sel) parts.push('selectable');
  return parts.filter(Boolean).join(', ');
}

function facelet(card) {
  const el = document.createElement('div');
  el.className = 'facelet';
  el.innerHTML = `<div class="fname">${escapeHtml(card.name || '')}</div>`
    + `<div class="ftype">${escapeHtml(card.type || '')}</div>`
    + `<div class="fcost">${symbols(card.cost || '')}</div>`;
  return el;
}

function shortCounter(type, n) {
  if (type === 'P1P1') return `+${n}/+${n}`;
  if (type === 'M1M1') return `-${n}/-${n}`;
  return `${type} ${n}`;
}

function imageUrl(card) {
  const params = new URLSearchParams();
  if (card.img) params.set('key', card.img);
  if (card.name) params.set('name', card.name);
  if (card.set) params.set('set', card.set);
  return `/api/card-image?${params}`;
}

function drawStack() {
  const el = $('stack-strip');
  el.replaceChildren(...state.stack.map((item) => {
    const div = document.createElement('div');
    div.className = 'stack-item' + (item.trigger ? ' trigger' : '');
    div.innerHTML = `<span class="src">${escapeHtml(item.srcName || '')}</span>`
      + `<span class="txt">${escapeHtml(item.text || '')}</span>`;
    div.onmouseenter = () => {
      const card = state.cards.get(item.src);
      if (card) inspect(card);
    };
    div.onmouseleave = hideInspector;
    return div;
  }));
}

function drawCombat() {
  const el = $('combat-strip');
  const bands = (state.combat && state.combat.bands) || [];
  el.replaceChildren(...bands.map((band) => {
    const attacker = state.cards.get(band.attacker);
    const blockers = (band.blockers || []).map((id) => state.cards.get(id)).filter(Boolean);
    const div = document.createElement('div');
    div.className = 'combat-line';
    const target = band.defenderName || '';
    div.textContent = blockers.length
      ? `${attacker?.name ?? '?'} → blocked by ${blockers.map((b) => b.name).join(', ')}`
      : `${attacker?.name ?? '?'} → ${target}`;
    return div;
  }));
}

function drawPrompt() {
  const prompt = state.prompt;
  const message = $('prompt-message');
  const ok = $('btn-ok');
  const cancel = $('btn-cancel');

  if (state.gameOver) {
    message.textContent = state.winner ? `${state.winner} wins.` : 'Game over.';
  } else {
    message.textContent = prompt ? prompt.message : 'Waiting…';
  }
  ok.textContent = prompt ? prompt.ok : 'OK';
  cancel.textContent = prompt ? prompt.cancel : 'Cancel';
  ok.disabled = !prompt || !prompt.okEnabled;
  cancel.disabled = !prompt || !prompt.cancelEnabled;
}

function drawManaPool(player) {
  const el = $('mana-pool');
  const pool = (player && player.mana) || {};
  el.replaceChildren(...Object.entries(pool).map(([color, n]) => {
    const div = document.createElement('div');
    div.className = `mana ${color}`;
    div.textContent = n;
    div.title = `Spend ${color} mana`;
    div.onclick = () => action('mana', { color });
    return div;
  }));
}

function drawLog() {
  const el = $('log-lines');
  if (el.childElementCount === state.log.length) return;
  el.replaceChildren(...state.log.map((line) => {
    const li = document.createElement('li');
    li.textContent = line;
    return li;
  }));
  el.scrollTop = el.scrollHeight;
}

// ------------------------------------------------------------------ inspector

function inspect(card) {
  if (card.hidden) return;
  const panel = $('inspector');
  const img = $('inspector-img');
  img.hidden = false;
  img.src = imageUrl(card);
  img.onerror = () => { img.hidden = true; };
  $('inspector-name').textContent = card.name || '';
  $('inspector-type').textContent = card.type || '';
  $('inspector-oracle').innerHTML = symbols(card.text || '');
  panel.hidden = false;
}

function hideInspector() {
  $('inspector').hidden = true;
}

// --------------------------------------------------------------------- dialogs

let dialogRid = null;

function openDialog(msg) {
  dialogRid = msg.rid;
  const backdrop = $('modal-backdrop');
  $('modal-title').textContent = msg.title || titleFor(msg.kind);
  $('modal-message').textContent = msg.message || '';
  const body = $('modal-body');
  const buttons = $('modal-buttons');
  body.replaceChildren();
  buttons.replaceChildren();

  switch (msg.kind) {
    case 'choice': buildChoice(msg, body, buttons); break;
    case 'confirm': buildConfirm(msg, buttons); break;
    case 'option': buildOptions(msg, buttons); break;
    case 'input': buildInput(msg, body, buttons); break;
    case 'amounts': buildAmounts(msg, body, buttons); break;
    default: answer({}); return;
  }
  backdrop.hidden = false;
}

function titleFor(kind) {
  return { choice: 'Choose', confirm: 'Confirm', option: 'Choose', input: 'Enter a value', amounts: 'Assign' }[kind] || 'Forge';
}

function answer(payload) {
  if (dialogRid === null) return;
  send({ t: 'answer', rid: dialogRid, ...payload });
  dialogRid = null;
  $('modal-backdrop').hidden = true;
}

function buildChoice(msg, body, buttons) {
  const picked = new Set(msg.selected || []);
  const order = [...picked];
  const min = msg.min ?? 0;
  const max = msg.max ?? 1;
  const revealOnly = min === 0 && max === 0;

  const grid = document.createElement('div');
  grid.className = 'choice-grid';
  const nodes = msg.options.map((option) => {
    const btn = document.createElement('button');
    btn.className = 'choice';
    btn.type = 'button';
    if (option.img || option.name) {
      const img = document.createElement('img');
      img.loading = 'lazy';
      img.src = `/api/card-image?${new URLSearchParams({ key: option.img || '', name: option.name || '' })}`;
      img.onerror = () => img.remove();
      btn.append(img);
    }
    const label = document.createElement('div');
    label.className = 'label';
    label.textContent = option.label;
    btn.append(label);
    btn.onclick = () => toggle(option.i, btn);
    return btn;
  });
  grid.append(...nodes);
  body.append(grid);

  const confirm = document.createElement('button');
  confirm.className = 'primary';
  confirm.textContent = revealOnly ? 'Done' : 'Confirm';
  confirm.onclick = () => answer({ picked: msg.ordered ? order : [...picked] });

  const cancel = document.createElement('button');
  cancel.className = 'secondary';
  cancel.textContent = 'Cancel';
  cancel.onclick = () => answer({ picked: [] });

  if (min > 0) {
    buttons.append(confirm);
  } else {
    buttons.append(cancel, confirm);
  }
  update();

  function toggle(index, btn) {
    if (revealOnly) return;
    if (picked.has(index)) {
      picked.delete(index);
      order.splice(order.indexOf(index), 1);
    } else {
      if (max === 1) {
        picked.clear();
        order.length = 0;
      } else if (max >= 0 && picked.size >= max) {
        return;
      }
      picked.add(index);
      order.push(index);
    }
    // A single forced pick is the common case; don't make the player press Confirm too.
    if (max === 1 && min === 1 && picked.size === 1) {
      answer({ picked: [...picked] });
      return;
    }
    update();
  }

  function update() {
    nodes.forEach((btn, i) => {
      const index = msg.options[i].i;
      btn.classList.toggle('picked', picked.has(index));
      const existing = btn.querySelector('.order-index');
      if (existing) existing.remove();
      if (msg.ordered && picked.has(index)) {
        const tag = document.createElement('span');
        tag.className = 'order-index';
        tag.textContent = order.indexOf(index) + 1;
        btn.append(tag);
      }
    });
    confirm.disabled = picked.size < min;
  }
}

function buildConfirm(msg, buttons) {
  (msg.options || ['Yes', 'No']).forEach((label, i) => {
    const btn = document.createElement('button');
    btn.className = i === 0 ? 'primary' : 'secondary';
    btn.textContent = label;
    btn.onclick = () => answer({ picked: i });
    buttons.append(btn);
  });
}

function buildOptions(msg, buttons) {
  (msg.options || []).forEach((label, i) => {
    const btn = document.createElement('button');
    btn.className = i === (msg.default ?? 0) ? 'primary' : 'secondary';
    btn.textContent = label;
    btn.onclick = () => answer({ picked: i });
    buttons.append(btn);
  });
}

function buildInput(msg, body, buttons) {
  const input = document.createElement('input');
  input.type = 'text';
  input.inputMode = msg.numeric ? 'numeric' : 'text';
  input.value = msg.initial || '';
  body.append(input);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') answer({ text: input.value });
  });

  const ok = document.createElement('button');
  ok.className = 'primary';
  ok.textContent = 'OK';
  ok.onclick = () => answer({ text: input.value });
  const cancel = document.createElement('button');
  cancel.className = 'secondary';
  cancel.textContent = 'Cancel';
  cancel.onclick = () => answer({});
  buttons.append(cancel, ok);
  setTimeout(() => input.focus(), 0);
}

function buildAmounts(msg, body, buttons) {
  const total = msg.amount ?? 0;
  const inputs = [];
  for (const option of msg.options || []) {
    const row = document.createElement('div');
    row.className = 'amount-row';
    const label = document.createElement('span');
    label.className = 'label';
    label.textContent = option.label;
    const input = document.createElement('input');
    input.type = 'number';
    input.min = '0';
    input.max = String(total);
    input.value = '0';
    input.dataset.index = option.i;
    input.oninput = update;
    inputs.push(input);
    row.append(label, input);
    body.append(row);
  }
  if (inputs.length) inputs[0].value = String(total);

  const remaining = document.createElement('p');
  remaining.className = 'amount-remaining';
  body.append(remaining);

  const ok = document.createElement('button');
  ok.className = 'primary';
  ok.textContent = 'Assign';
  ok.onclick = () => {
    const amounts = {};
    for (const input of inputs) {
      const value = Number(input.value) || 0;
      if (value > 0) amounts[input.dataset.index] = value;
    }
    answer({ amounts });
  };
  buttons.append(ok);
  update();

  function update() {
    const used = inputs.reduce((sum, input) => sum + (Number(input.value) || 0), 0);
    const left = total - used;
    remaining.textContent = `${left} of ${total} left to assign`;
    remaining.classList.toggle('over', left < 0);
    ok.disabled = left < 0 || (msg.atLeastOne && used === 0);
  }
}

function showZone(title, ids) {
  const cards = (ids || []).map((id) => state.cards.get(id)).filter(Boolean);
  if (!cards.length) return;
  dialogRid = null;
  $('modal-title').textContent = title;
  $('modal-message').textContent = '';
  const body = $('modal-body');
  const grid = document.createElement('div');
  grid.className = 'choice-grid';
  grid.append(...cards.map((card) => {
    const div = document.createElement('button');
    div.className = 'choice';
    div.type = 'button';
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.src = imageUrl(card);
    img.onerror = () => img.remove();
    const label = document.createElement('div');
    label.className = 'label';
    label.textContent = card.name;
    div.append(img, label);
    div.onclick = () => { closeZone(); action('card', { id: card.id }); };
    return div;
  }));
  body.replaceChildren(grid);

  const close = document.createElement('button');
  close.className = 'secondary';
  close.textContent = 'Close';
  close.onclick = closeZone;
  $('modal-buttons').replaceChildren(close);
  $('modal-backdrop').hidden = false;

  function closeZone() {
    $('modal-backdrop').hidden = true;
  }
}

// ---------------------------------------------------------------------- misc

function toast(message, isError) {
  if (!message) return;
  const el = document.createElement('div');
  el.className = 'toast' + (isError ? ' error' : '');
  el.textContent = message;
  $('toasts').append(el);
  setTimeout(() => el.remove(), 5000);
}

function flashPrompt() {
  const el = $('prompt');
  el.animate(
    [{ background: '#161b22' }, { background: '#4a2224' }, { background: '#161b22' }],
    { duration: 320, easing: 'ease-out' },
  );
}

function symbols(text) {
  return escapeHtml(text).replace(/\{([^}]+)\}/g, (_, symbol) => {
    const cls = /^[WUBRG]$/.test(symbol) ? ` ${symbol}` : '';
    return `<span class="sym${cls}">${symbol}</span>`;
  });
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

// ---------------------------------------------------------------------- setup

async function loadDecks() {
  try {
    const decks = await (await fetch('/api/decks')).json();
    for (const id of ['deck', 'opponent-deck']) {
      const select = $(id);
      for (const name of decks) {
        const option = document.createElement('option');
        option.value = name;
        option.textContent = name;
        select.append(option);
      }
    }
  } catch (e) {
    // No saved decks is fine; the random generator still works.
  }
}

$('start').onclick = async () => {
  const button = $('start');
  button.disabled = true;
  button.textContent = 'Shuffling…';
  $('setup-error').hidden = true;
  try {
    const response = await fetch('/api/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deck: $('deck').value, opponentDeck: $('opponent-deck').value }),
    });
    const result = await response.json();
    if (!result.ok) throw new Error(result.error || 'Could not start the game.');
  } catch (e) {
    $('setup-error').textContent = e.message;
    $('setup-error').hidden = false;
    button.disabled = false;
    button.textContent = 'Start game';
  }
};

$('btn-ok').onclick = () => action('ok');
$('btn-cancel').onclick = () => action('cancel');
$('btn-concede').onclick = () => {
  if (confirm('Concede this game?')) action('concede');
};
$('btn-log').onclick = () => { $('logpanel').hidden = false; drawLog(); };
$('btn-log-close').onclick = () => { $('logpanel').hidden = true; };

document.addEventListener('keydown', (e) => {
  if (dialogRid !== null || $('setup').hidden === false) return;
  if (e.key === ' ' || e.key === 'Enter') {
    if (!$('btn-ok').disabled) { e.preventDefault(); action('ok'); }
  } else if (e.key === 'Escape') {
    if (!$('btn-cancel').disabled) action('cancel');
  } else if (e.key === 'z' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    action('undo');
  }
});

loadDecks();
connect();
