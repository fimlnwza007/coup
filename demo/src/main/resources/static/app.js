const landingView = document.querySelector("#landingView");
const roomView = document.querySelector("#roomView");
const roomForm = document.querySelector("#roomForm");
const nameInput = document.querySelector("#playerName");
const codeInput = document.querySelector("#roomCode");
const formError = document.querySelector("#formError");
const createButton = document.querySelector("#createButton");
const actionDialog = document.querySelector("#actionDialog");
const dialogContent = document.querySelector("#dialogContent");

const playerId = getPlayerId();
let room = null;
let pollTimer = null;
let pendingAction = null;

nameInput.value = localStorage.getItem("coup-player-name") || "";
codeInput.addEventListener("input", () => {
  codeInput.value = codeInput.value.replace(/[^a-z0-9]/gi, "").toUpperCase().slice(0, 6);
});

function getPlayerId() {
  let id = localStorage.getItem("coup-player-id");
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem("coup-player-id", id);
  }
  return id;
}

async function request(path, body) {
  const response = await fetch(path, {
    method: body ? "POST" : "GET",
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.detail || data.message || "มีบางอย่างผิดพลาด ลองอีกครั้งนะ");
  }
  return response.json();
}

function setError(message = "") {
  formError.textContent = message;
}

function getName() {
  const name = nameInput.value.trim();
  if (!name) {
    nameInput.focus();
    setError("ใส่ชื่อเล่นก่อนเริ่มเล่นนะ");
    return null;
  }
  localStorage.setItem("coup-player-name", name);
  setError();
  return name;
}

roomForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const name = getName();
  if (!name) return;
  if (!codeInput.value.trim()) {
    setError("ใส่รหัสห้อง หรือเลือกสร้างห้องใหม่");
    codeInput.focus();
    return;
  }
  await runEntry(async () => request(`/api/rooms/${encodeURIComponent(codeInput.value.trim())}/join`, { name, playerId }));
});

createButton.addEventListener("click", async () => {
  const name = getName();
  if (!name) return;
  await runEntry(async () => request("/api/rooms", { name, playerId }));
});

async function runEntry(action) {
  setError();
  createButton.disabled = true;
  document.querySelector("#enterButton").disabled = true;
  try {
    updateRoom(await action());
  } catch (error) {
    setError(error.message);
  } finally {
    createButton.disabled = false;
    document.querySelector("#enterButton").disabled = false;
  }
}

function updateRoom(nextRoom) {
  if (room && JSON.stringify(room) === JSON.stringify(nextRoom)) return;
  room = nextRoom;
  localStorage.setItem("coup-room-code", room.code);
  landingView.classList.add("hidden");
  roomView.classList.remove("hidden");
  renderRoom();
  if (!pollTimer) pollTimer = window.setInterval(refreshRoom, 1800);
  if (room.exchangeOptions?.length && !actionDialog.open) openExchangeDialog();
}

async function refreshRoom() {
  if (!room) return;
  try {
    updateRoom(await request(`/api/rooms/${room.code}?playerId=${encodeURIComponent(playerId)}`));
  } catch (error) {
    if (error.message.includes("ไม่พบห้อง")) {
      returnToLobby();
      showToast("ห้องนี้หมดอายุแล้ว สร้างห้องใหม่ได้เลย");
      return;
    }
    showToast(error.message);
  }
}

function returnToLobby() {
  if (pollTimer) window.clearInterval(pollTimer);
  pollTimer = null;
  room = null;
  localStorage.removeItem("coup-room-code");
  roomView.classList.add("hidden");
  landingView.classList.remove("hidden");
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function renderRoom() {
  if (!room) return;
  const self = room.players.find((player) => player.id === playerId);
  const isHost = room.hostId === playerId;
  const isTurn = room.currentPlayerId === playerId;
  const reactionPlayer = room.pendingAction && room.players.find((player) => player.id === room.pendingAction.reactionPlayerId);
  const heading = room.pendingAction
    ? reactionPlayer?.id === playerId ? "มีคำอ้างให้ตัดสินใจ" : `รอ ${reactionPlayer?.name || "ผู้เล่น"} ตอบโต้`
    : room.started ? room.finished ? "เกมสิ้นสุด" : isTurn ? "ถึงตาของคุณ" : "รอผู้เล่นเลือกคำสั่ง" : "พร้อมเปิดวงหรือยัง?";
  roomView.innerHTML = `
    <div class="room-topline">
      <button class="text-button" id="backButton">← &nbsp;กลับหน้าหลัก</button>
      <span class="room-overline">วงเล่น &nbsp;/&nbsp; COUP</span>
    </div>
    <div class="room-heading">
      <div><p class="room-overline">${room.pendingAction ? "กำลังโต้ตอบ" : room.started ? (room.finished ? "จบเกมแล้ว" : "กำลังเล่น") : "ห้องรอผู้เล่น"}</p><h1>${heading}</h1></div>
      <div class="room-code"><span class="room-code-label">รหัสชวนเพื่อน · กดเพื่อคัดลอก</span><button id="copyCode" aria-label="คัดลอกรหัสห้อง">${escapeHTML(room.code)}</button></div>
    </div>
    <div class="room-layout">
      <div class="room-main">
        ${room.started ? renderTable(self, isTurn) : renderLobby(isHost)}
      </div>
      <aside class="room-sidebar">
        <div class="section-heading"><h2>ผู้เล่น</h2><span>${room.players.length} / 6</span></div>
        <div class="player-grid">${room.players.map((player) => renderPlayer(player, room.started)).join("")}</div>
        <div class="lobby-aside" style="margin-top:20px">
          <h3>บันทึกในวง</h3>
          ${room.log.length ? `<ul class="event-log">${room.log.slice(-6).reverse().map((line) => `<li>${escapeHTML(line)}</li>`).join("")}</ul>` : `<p class="empty-log">ยังไม่มีความเคลื่อนไหว</p>`}
        </div>
      </aside>
    </div>`;

  document.querySelector("#backButton").addEventListener("click", returnToLobby);
  document.querySelector("#copyCode").addEventListener("click", copyRoomCode);
  const startButton = document.querySelector("#startGame");
  if (startButton) startButton.addEventListener("click", startGame);
  roomView.querySelectorAll("[data-action]").forEach((button) => button.addEventListener("click", () => chooseAction(button.dataset.action)));
  roomView.querySelectorAll("[data-response]").forEach((button) => button.addEventListener("click", () => sendResponse(button.dataset.response)));
  const copyLink = document.querySelector("#copyLink");
  if (copyLink) copyLink.addEventListener("click", copyRoomCode);
}

function renderLobby(isHost) {
  const canStart = room.players.length >= 2;
  return `
    <div class="lobby-band">
      <div class="lobby-message">${isHost ? `ส่งรหัส <strong>${escapeHTML(room.code)}</strong> ให้เพื่อน แล้วเริ่มเกมได้เลย` : `รอเจ้าของห้องเริ่มเกม · ส่งรหัส <strong>${escapeHTML(room.code)}</strong> ให้เพื่อนเพิ่มได้`}</div>
      ${isHost ? `<button class="button button-primary" id="startGame" ${canStart ? "" : "disabled"}>เริ่มเกม <span aria-hidden="true">↗</span></button>` : ""}
    </div>
    <div class="lobby-aside" style="margin-top:14px"><h3>${canStart ? "ครบแล้ว พร้อมเล่น" : `รอเพื่อนอีก ${2 - room.players.length} คน`}</h3><p>เกมเริ่มได้ตั้งแต่ 2 คน สูงสุด 6 คน ผู้เล่นแต่ละคนจะได้รับอิทธิพลลับ 2 ใบและเหรียญ 2 เหรียญ</p><button class="copy-link" id="copyLink">คัดลอกรหัสห้อง ${escapeHTML(room.code)} ↗</button></div>`;
}

function renderTable(self, isTurn) {
  const roleNames = { Duke: "ดยุก", Assassin: "นักฆ่า", Captain: "กัปตัน", Ambassador: "ทูต", Contessa: "คอนเตสซา" };
  const hand = self.cards.map((card) => `<div class="influence-card ${card.alive ? "" : "lost"}"><span class="influence-mark">${card.alive ? "✦" : "×"}</span><span class="influence-role">${escapeHTML(card.role)}</span><span class="influence-caption">${roleNames[card.role] || "อิทธิพล"} · ${card.alive ? "ยังอยู่ในเกม" : "เสียอิทธิพลแล้ว"}</span></div>`).join("");
  if (room.pendingAction) {
    const pending = room.pendingAction;
    const actionNames = { AID: "ขอความช่วยเหลือ", TAX: "เก็บภาษี", STEAL: "ขโมย", ASSASSINATE: "ลอบสังหาร", EXCHANGE: "แลกอิทธิพล" };
    const roleNamesForClaim = { Duke: "Duke · ดยุก", Assassin: "Assassin · นักฆ่า", Captain: "Captain · กัปตัน", Ambassador: "Ambassador · ทูต", Contessa: "Contessa · คอนเตสซา", "Captain/Ambassador": "Captain หรือ Ambassador" };
    const isResponder = pending.reactionPlayerId === playerId;
    const actionText = actionNames[pending.action] || "คำสั่ง";
    const targetText = pending.targetName ? ` ไปยัง ${escapeHTML(pending.targetName)}` : "";
    const responseButtons = isResponder
      ? `<div class="response-actions"><button class="response-button" data-response="ALLOW">ยอมให้ผ่าน<span>ไม่ท้าทายคำอ้าง</span></button><button class="response-button response-challenge" data-response="CHALLENGE">ท้าทาย<span>พิสูจน์ว่าเป็น ${escapeHTML(roleNamesForClaim[pending.claimedRole] || pending.claimedRole)}</span></button>${pending.canBlock ? `<button class="response-button response-block" data-response="BLOCK">ขัดขวาง<span>อ้าง ${escapeHTML(roleNamesForClaim[pending.blockRole] || pending.blockRole)}</span></button>` : ""}</div>`
      : `<p class="reaction-wait">รอ ${escapeHTML(room.players.find((player) => player.id === pending.reactionPlayerId)?.name || "ผู้เล่น")} ตอบโต้คำอ้างนี้</p>`;
    return `
      <div class="table-banner"><div><strong>${escapeHTML(pending.claimantName)} อ้างว่าเป็น ${escapeHTML(roleNamesForClaim[pending.claimedRole] || pending.claimedRole)}</strong><br><span>${actionText}${targetText} · ${isResponder ? "เลือกการตอบโต้ของคุณ" : "ทุกคนกำลังจับตาดู"}</span></div><span>สำรับเหลือ ${room.cardsRemaining} ใบ</span></div>
      <section class="hand-area"><div class="section-heading"><h2>อิทธิพลของคุณ</h2><span>เก็บเป็นความลับจากคนอื่น</span></div><div class="hand-cards">${hand}</div></section>
      <section class="action-area"><div class="section-heading"><h2>ตอบโต้</h2><span>${isResponder ? "เลือกหนึ่งอย่าง" : "รอผู้เล่นที่ถึงคิว"}</span></div>${responseButtons}</section>`;
  }
  const actions = [
    ["INCOME", "รับรายได้", "+1 เหรียญ · ทำได้เสมอ"],
    ["AID", "ขอความช่วยเหลือ", "+2 เหรียญ · อ้าง Duke"],
    ["TAX", "เก็บภาษี", "+3 เหรียญ · Duke"],
    ["STEAL", "ขโมย", "เอาสูงสุด 2 · Captain"],
    ["EXCHANGE", "แลกอิทธิพล", "จั่วแล้วเลือกเก็บ · Ambassador"],
    ["ASSASSINATE", "ลอบสังหาร", "จ่าย 3 · Assassin"],
    ["COUP", "รัฐประหาร", "จ่าย 7 · เลือกเป้าหมาย"]
  ];
  const buttons = actions.map(([type, title, detail]) => {
    const needsTarget = ["STEAL", "ASSASSINATE", "COUP"].includes(type);
    const tooPoor = (type === "ASSASSINATE" && self.coins < 3) || (type === "COUP" && self.coins < 7);
    const forcedCoup = self.coins >= 10 && type !== "COUP";
    return `<button class="action-button" data-action="${type}" ${!isTurn || room.finished || tooPoor || forcedCoup ? "disabled" : ""}><strong>${title}</strong><span>${detail}</span></button>`;
  }).join("");
  return `
    <div class="table-banner"><div><strong>${room.finished ? "วงนี้จบแล้ว" : isTurn ? "เทิร์นของคุณ" : `ถึงตา ${escapeHTML(room.players.find((p) => p.id === room.currentPlayerId)?.name || "ผู้เล่น")}`}</strong><br><span>${room.finished ? "ดูบันทึกการเล่นด้านข้าง" : isTurn ? "เลือกหนึ่งคำสั่งเพื่อเล่นต่อ" : "เกมจะอัปเดตอัตโนมัติเมื่อมีคนเดิน"}</span></div><span>สำรับเหลือ ${room.cardsRemaining} ใบ</span></div>
    <section class="hand-area"><div class="section-heading"><h2>อิทธิพลของคุณ</h2><span>เก็บเป็นความลับจากคนอื่น</span></div><div class="hand-cards">${hand}</div></section>
    <section class="action-area"><div class="section-heading"><h2>เลือกคำสั่ง</h2><span>${isTurn ? "เลือกได้หนึ่งอย่างต่อเทิร์น" : "รอถึงเทิร์นของคุณ"}</span></div><div class="action-grid">${buttons}</div></section>`;
}

function renderPlayer(player, started) {
  const current = room.currentPlayerId === player.id;
  const self = room.selfId === player.id;
  const host = room.hostId === player.id;
  const influence = started ? `${player.influences} อิทธิพล` : host ? "เจ้าของห้อง" : "รอเริ่มเกม";
  return `<article class="player-seat ${current ? "is-current" : ""} ${player.alive ? "" : "is-out"}"><div class="seat-top"><span class="seat-name">${escapeHTML(player.name)}${self ? " (คุณ)" : ""}</span><span class="seat-tag">${current ? "ตานี้" : host && !started ? "เจ้าของ" : ""}</span></div><div class="seat-detail"><span>${influence}</span>${started ? `<span class="coin-count">◉ ${player.coins}</span>` : ""}</div></article>`;
}

async function startGame() {
  try {
    updateRoom(await request(`/api/rooms/${room.code}/start`, { playerId }));
  } catch (error) {
    showToast(error.message);
  }
}

async function sendResponse(response) {
  try {
    updateRoom(await request(`/api/rooms/${room.code}/action`, { playerId, action: response, targetId: null, keepIndexes: null }));
  } catch (error) {
    showToast(error.message);
  }
}

function chooseAction(type) {
  pendingAction = { type, targetId: null, keepIndexes: null };
  if (["STEAL", "ASSASSINATE", "COUP"].includes(type)) {
    const targets = room.players.filter((player) => player.id !== playerId && player.alive);
    openTargetDialog(targets);
  } else if (type === "EXCHANGE") {
    submitAction();
  } else {
    submitAction();
  }
}

function openExchangeDialog() {
  const self = room.players.find((player) => player.id === playerId);
  const selected = new Set();
  const required = self.influences;
  const roleNames = { Duke: "ดยุก", Assassin: "นักฆ่า", Captain: "กัปตัน", Ambassador: "ทูต", Contessa: "คอนเตสซา" };
  dialogContent.innerHTML = `<div class="dialog-content"><span class="room-overline">แลกอิทธิพล</span><h2>เลือกเก็บ ${required} ใบ</h2><p>เลือกไพ่ที่ต้องการเก็บ ไพ่ที่เหลือจะกลับไปใต้สำรับ</p><div class="exchange-list">${room.exchangeOptions.map((role, index) => `<button class="exchange-choice" data-index="${index}"><span class="influence-mark">✦</span><strong>${escapeHTML(roleNames[role] || role)}</strong></button>`).join("")}</div><button class="button button-primary dialog-submit" id="confirmExchange" disabled>เลือกให้ครบ ${required} ใบ</button></div>`;
  dialogContent.querySelectorAll("[data-index]").forEach((button) => button.addEventListener("click", () => {
    const index = Number(button.dataset.index);
    if (selected.has(index)) selected.delete(index);
    else if (selected.size < required) selected.add(index);
    button.classList.toggle("selected", selected.has(index));
    const confirm = dialogContent.querySelector("#confirmExchange");
    confirm.disabled = selected.size !== required;
    confirm.innerHTML = selected.size === required ? "ยืนยันไพ่ที่เลือก <span>↗</span>" : `เลือกให้ครบ ${required} ใบ`;
  }));
  dialogContent.querySelector("#confirmExchange").addEventListener("click", () => {
    pendingAction = { type: "EXCHANGE", targetId: null, keepIndexes: [...selected] };
    submitAction();
  });
  actionDialog.showModal();
}

function openTargetDialog(targets) {
  const labels = { STEAL: "ขโมย", ASSASSINATE: "ลอบสังหาร", COUP: "รัฐประหาร" };
  dialogContent.innerHTML = `<div class="dialog-content"><span class="room-overline">เลือกเป้าหมาย</span><h2>${labels[pendingAction.type]}</h2><p>เลือกผู้เล่นที่ต้องการส่งคำสั่งนี้ไปหา</p><div class="target-list">${targets.map((target) => `<button class="target-choice" data-target="${target.id}"><strong>${escapeHTML(target.name)}</strong><span>${target.coins} เหรียญ · ${target.influences} อิทธิพล</span></button>`).join("")}</div></div>`;
  dialogContent.querySelectorAll("[data-target]").forEach((button) => button.addEventListener("click", () => {
    pendingAction.targetId = button.dataset.target;
    actionDialog.close();
    submitAction();
  }));
  actionDialog.showModal();
}

async function submitAction() {
  try {
    updateRoom(await request(`/api/rooms/${room.code}/action`, {
      playerId,
      action: pendingAction.type,
      targetId: pendingAction.targetId,
      keepIndexes: pendingAction.keepIndexes
    }));
    actionDialog.close();
  } catch (error) {
    actionDialog.close();
    showToast(error.message);
  }
}

async function copyRoomCode() {
  try {
    await navigator.clipboard.writeText(room.code);
    showToast("คัดลอกรหัสห้องแล้ว");
  } catch {
    showToast(`รหัสห้อง ${room.code}`);
  }
}

function escapeHTML(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
}

function showToast(message) {
  document.querySelector(".toast")?.remove();
  const toast = document.createElement("div");
  toast.className = "toast";
  toast.textContent = message;
  document.body.append(toast);
  window.setTimeout(() => toast.remove(), 2600);
}

async function restoreRoom() {
  const code = localStorage.getItem("coup-room-code");
  if (!code) return;
  try {
    updateRoom(await request(`/api/rooms/${code}?playerId=${encodeURIComponent(playerId)}`));
  } catch {
    localStorage.removeItem("coup-room-code");
  }
}

restoreRoom();