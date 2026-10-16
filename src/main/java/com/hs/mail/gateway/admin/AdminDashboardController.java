package com.hs.mail.gateway.admin;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 통계 대시보드 - /actuator/gateway(카운터)와 /admin/mail-list(화이트/블랙리스트)를
 * 주기적으로 폴링해 보여주는 단일 HTML 페이지. 별도 프론트엔드 빌드 없이 순수 JS로 동작한다.
 */
@RestController
public class AdminDashboardController {

    @GetMapping(value = "/admin/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"ko\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<title>Hedwig Spam Gateway - 관리자 대시보드</title>\n" +
                "<style>\n" +
                "  body { font-family: -apple-system, sans-serif; margin: 2rem; background: #f5f6f8; color: #222; }\n" +
                "  h1 { font-size: 1.4rem; }\n" +
                "  .cards { display: flex; flex-wrap: wrap; gap: 1rem; margin-bottom: 2rem; }\n" +
                "  .card { background: white; border-radius: 8px; padding: 1rem 1.5rem; box-shadow: 0 1px 3px rgba(0,0,0,0.1); min-width: 180px; }\n" +
                "  .card h3 { margin: 0 0 0.5rem; font-size: 0.85rem; color: #666; font-weight: 600; }\n" +
                "  .card .value { font-size: 1.6rem; font-weight: 700; }\n" +
                "  table { width: 100%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }\n" +
                "  th, td { padding: 0.5rem 0.8rem; text-align: left; border-bottom: 1px solid #eee; font-size: 0.9rem; }\n" +
                "  th { background: #fafafa; }\n" +
                "  form { display: flex; gap: 0.5rem; margin: 1rem 0; flex-wrap: wrap; align-items: center; }\n" +
                "  input, select { padding: 0.4rem; border: 1px solid #ccc; border-radius: 4px; }\n" +
                "  button { padding: 0.4rem 0.9rem; border: none; border-radius: 4px; background: #2563eb; color: white; cursor: pointer; }\n" +
                "  button.danger { background: #dc2626; }\n" +
                "  section { margin-bottom: 2rem; }\n" +
                "  .tabs { display: flex; gap: 0.25rem; border-bottom: 2px solid #e5e7eb; margin: 0 0 1.25rem; }\n" +
                "  .tab { background: transparent; color: #555; border-radius: 6px 6px 0 0; padding: 0.6rem 1.1rem; font-size: 0.95rem; border-bottom: 3px solid transparent; margin-bottom: -2px; }\n" +
                "  .tab:hover { background: #eef2ff; }\n" +
                "  .tab.active { color: #1d4ed8; font-weight: 700; border-bottom-color: #2563eb; background: white; }\n" +
                "  .tab .badge { color: #dc2626; font-weight: 700; }\n" +
                "  .tab-panel { display: none; }\n" +
                "  .tab-panel.active { display: block; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<h1>Hedwig Spam Gateway 관리자 대시보드</h1>\n" +
                "<form id=\"keyForm\" style=\"display:none;background:white;padding:0.6rem 1rem;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.1);margin:0 0 1.5rem\">\n" +
                "  <label for=\"adminKey\" style=\"font-size:0.85rem;color:#555\">관리자 키</label>\n" +
                "  <input id=\"adminKey\" type=\"password\" autocomplete=\"off\" placeholder=\"설정된 경우에만 입력\" size=\"28\">\n" +
                "  <button type=\"submit\">적용</button>\n" +
                "  <button type=\"button\" id=\"keyClear\" style=\"background:#6b7280\">지우기</button>\n" +
                "  <span id=\"authStatus\" style=\"font-size:0.85rem\"></span>\n" +
                "</form>\n" +
                "<form id=\"loginForm\" style=\"display:none;background:white;padding:0.6rem 1rem;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.1);margin:0 0 1.5rem\">\n" +
                "  <label for=\"loginUser\" style=\"font-size:0.85rem;color:#555\">ID</label>\n" +
                "  <input id=\"loginUser\" type=\"text\" autocomplete=\"username\" size=\"16\">\n" +
                "  <label for=\"loginPass\" style=\"font-size:0.85rem;color:#555\">비밀번호</label>\n" +
                "  <input id=\"loginPass\" type=\"password\" autocomplete=\"current-password\" size=\"20\">\n" +
                "  <button type=\"submit\">로그인</button>\n" +
                "  <span id=\"loginStatus\" style=\"font-size:0.85rem\"></span>\n" +
                "</form>\n" +
                "<div id=\"sessionBar\" style=\"display:none;background:white;padding:0.6rem 1rem;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.1);margin:0 0 1.5rem;font-size:0.9rem\">\n" +
                "  <b id=\"sessionUser\"></b> 님으로 로그인됨 <button type=\"button\" id=\"logoutBtn\" style=\"background:#6b7280;margin-left:0.5rem\">로그아웃</button>\n" +
                "</div>\n" +
                "<section>\n" +
                "  <div class=\"cards\" id=\"cards\">불러오는 중...</div>\n" +
                "</section>\n" +
                "<nav class=\"tabs\" id=\"tabs\">\n" +
                "  <button class=\"tab\" data-tab=\"rules\">룰 관리</button>\n" +
                "  <button class=\"tab\" data-tab=\"reports\">스팸 신고<span class=\"badge\" id=\"badgeReports\"></span></button>\n" +
                "  <button class=\"tab\" data-tab=\"events\">통계·로그</button>\n" +
                "  <button class=\"tab\" data-tab=\"chat\">AI 챗</button>\n" +
                "  <button class=\"tab\" data-tab=\"lists\">화이트/블랙리스트</button>\n" +
                "</nav>\n" +
                "<section class=\"tab-panel\" id=\"tab-lists\">\n" +
                "  <h2>화이트/블랙리스트</h2>\n" +
                "  <form id=\"addForm\">\n" +
                "    <select id=\"listType\"><option value=\"WHITE\">화이트리스트</option><option value=\"BLACK\">블랙리스트</option></select>\n" +
                "    <input id=\"pattern\" placeholder=\"user@domain.com 또는 @domain.com\" size=\"28\" required>\n" +
                "    <input id=\"recipient\" placeholder=\"수신자(비우면 전역)\" size=\"20\">\n" +
                "    <input id=\"reason\" placeholder=\"사유(선택)\" size=\"20\">\n" +
                "    <button type=\"submit\">추가</button>\n" +
                "  </form>\n" +
                "  <table id=\"listTable\">\n" +
                "    <thead><tr><th>유형</th><th>패턴</th><th>수신자</th><th>사유</th><th></th></tr></thead>\n" +
                "    <tbody></tbody>\n" +
                "  </table>\n" +
                "</section>\n" +
                "<section class=\"tab-panel\" id=\"tab-rules\">\n" +
                "  <h2>룰기반 스팸 필터 - 룰 관리</h2>\n" +
                "  <p style=\"color:#666;font-size:0.85rem;margin-top:-0.5rem\">키워드(정규식)/브랜드 사칭/프리메일 도메인/URL 단축서비스/구조체크 가중치를 재빌드 없이 여기서 바로 추가·수정·삭제합니다.</p>\n" +
                "  <form id=\"ruleAddForm\">\n" +
                "    <select id=\"ruleType\">\n" +
                "      <option value=\"KEYWORD\">KEYWORD(정규식)</option>\n" +
                "      <option value=\"BRAND\">BRAND(브랜드명)</option>\n" +
                "      <option value=\"FREE_MAIL_DOMAIN\">FREE_MAIL_DOMAIN(도메인)</option>\n" +
                "      <option value=\"URL_SHORTENER\">URL_SHORTENER(도메인)</option>\n" +
                "      <option value=\"STRUCTURAL\">STRUCTURAL(식별자)</option>\n" +
                "    </select>\n" +
                "    <input id=\"rulePattern\" placeholder=\"패턴/도메인/브랜드명/식별자\" size=\"28\" required>\n" +
                "    <input id=\"ruleWeight\" type=\"number\" step=\"0.5\" placeholder=\"가중치\" value=\"2.0\" size=\"6\">\n" +
                "    <input id=\"ruleReason\" placeholder=\"설명(선택)\" size=\"20\">\n" +
                "    <button type=\"submit\">추가</button>\n" +
                "  </form>\n" +
                "  <table id=\"ruleTable\">\n" +
                "    <thead><tr><th>ID</th><th>유형</th><th>패턴</th><th>가중치</th><th>적중(스팸판정)</th><th>상태</th><th>설명</th><th></th></tr></thead>\n" +
                "    <tbody></tbody>\n" +
                "  </table>\n" +
                "  <div id=\"advicePanel\" style=\"display:none;position:fixed;inset:0;background:rgba(0,0,0,0.45);z-index:1000;align-items:center;justify-content:center\"><div id=\"adviceBox\" style=\"background:white;padding:1.5rem;border-radius:10px;max-width:640px;width:90%;max-height:80vh;overflow:auto;box-shadow:0 10px 30px rgba(0,0,0,0.3)\"></div></div>\n" +
                "</section>\n" +
                "<section class=\"tab-panel\" id=\"tab-reports\">\n" +
                "  <h2>사용자 스팸 신고 (LLM 판정)</h2>\n" +
                "  <p style=\"font-size:0.85rem;color:#555\">신고 → LLM이 판정/의견 → 확신도 높은 스팸은 RAG 사례로 자동 편입 → 룰 추가는 여기서 승인해야 반영됩니다.</p>\n" +
                "  <div id=\"reportSettings\" style=\"border:1px solid #ddd;border-radius:8px;padding:0.6rem 0.8rem;margin:0.6rem 0;font-size:0.85rem;background:#fafafa\">\n" +
                "    <label><b><input type=\"checkbox\" id=\"rsAuto\"> 자동 처리</b></label> <span style=\"color:#555\">(꺼짐=수동: 관리자가 승인해야 룰 반영 / 켜짐: LLM 확신도가 기준 이상이면 승인 없이 룰 추가)</span><br>\n" +
                "    확신도 ≥ <input type=\"number\" id=\"rsConf\" min=\"0.5\" max=\"1\" step=\"0.05\" style=\"width:4.5rem\">\n" +
                "    룰 가중치 상한 <input type=\"number\" id=\"rsW\" min=\"0.1\" max=\"5\" step=\"0.5\" style=\"width:4rem\">\n" +
                "    하루 자동 추가 상한 <input type=\"number\" id=\"rsMax\" min=\"0\" max=\"1000\" step=\"1\" style=\"width:4.5rem\">\n" +
                "    <label><input type=\"checkbox\" id=\"rsHam\"> 정상 판정(확신도 이상)은 자동 기각</label>\n" +
                "    <button id=\"rsSave\">저장</button> <span id=\"rsStatus\" style=\"margin-left:0.5rem\"></span>\n" +
                "  </div>\n" +
                "  <table id=\"reportTable\">\n" +
                "    <thead><tr><th>ID</th><th>신고자</th><th>발신 도메인</th><th>제목 / 본문 일부</th><th>LLM 판정</th><th>의견</th><th>제안 룰</th><th>상태</th><th>RAG</th><th></th></tr></thead>\n" +
                "    <tbody></tbody>\n" +
                "  </table>\n" +
                "</section>\n" +
                "<section class=\"tab-panel\" id=\"tab-chat\">\n" +
                "  <h2>AI 챗 - 스팸 서버 질의</h2>\n" +
                "  <p style=\"font-size:0.85rem;color:#555\">통계와 룰 현황을 말로 물어보세요. 룰 변경(추가, 켜기/끄기, 가중치)을 말하면 챗봇은 <b>제안</b>만 만들고, 아래 [적용]을 눌러야 반영됩니다. 모든 변경은 이력에 남습니다.</p>\n" +
                "  <div id=\"chatSamples\" style=\"margin:0.5rem 0\"></div>\n" +
                "  <div id=\"chatLog\" style=\"background:white;border:1px solid #ddd;border-radius:8px;padding:0.8rem;min-height:200px;max-height:460px;overflow:auto\"></div>\n" +
                "  <form id=\"chatForm\" onsubmit=\"return false\">\n" +
                "    <input id=\"chatInput\" placeholder=\"예: 어제 스팸 몇 건이야?\" size=\"60\" maxlength=\"300\" autocomplete=\"off\">\n" +
                "    <button id=\"chatSend\">질문</button>\n" +
                "  </form>\n" +
                "  <h3>최근 변경 이력 <button type=\"button\" id=\"auditReload\" style=\"font-size:0.75rem\">새로고침</button></h3>\n" +
                "  <table id=\"auditTable\"><thead><tr><th>시각</th><th>관리자</th><th>경로</th><th>동작</th><th>대상</th><th>변경 전</th><th>변경 후</th></tr></thead><tbody></tbody></table>\n" +
                "</section>\n" +
                "<section class=\"tab-panel\" id=\"tab-events\">\n" +
                "  <h2>메일 처리 통계·로그</h2>\n" +
                "  <form id=\"evForm\" onsubmit=\"return false\">\n" +
                "    기간 <select id=\"evPeriod\"><option value=\"today\">오늘</option><option value=\"yesterday\">어제</option><option value=\"last7d\" selected>최근 7일</option><option value=\"last30d\">최근 30일</option><option value=\"thisweek\">이번 주</option><option value=\"thismonth\">이번 달</option><option value=\"custom\">직접 입력</option></select>\n" +
                "    <input type=\"date\" id=\"evFrom\"> ~ <input type=\"date\" id=\"evTo\">\n" +
                "    <button id=\"evReload\">조회</button>\n" +
                "  </form>\n" +
                "  <div class=\"cards\" id=\"evCards\"></div>\n" +
                "  <h3>발신 인증 결과 (SPF · DKIM · DMARC)</h3>\n" +
                "  <table id=\"evAuth\"><thead><tr><th>결과</th><th>SPF</th><th>DKIM</th><th>DMARC</th></tr></thead><tbody></tbody></table>\n" +
                "  <h3>일자별 추이</h3>\n" +
                "  <table id=\"evDaily\"><thead><tr><th>일자</th><th>정상</th><th>스팸</th><th>거부</th><th>지연</th><th>스팸 비율</th></tr></thead><tbody></tbody></table>\n" +
                "  <h3>항목별 상위\n" +
                "    <select id=\"evDim\"><option value=\"domain\">발신 도메인</option><option value=\"ip\">발신 IP</option><option value=\"sender\">발신자</option><option value=\"filter\">걸린 필터</option><option value=\"recipient_domain\">수신 도메인</option></select>\n" +
                "    <select id=\"evTopAction\"><option value=\"SPAM\">스팸</option><option value=\"REJECTED\">거부</option><option value=\"DEFERRED\">지연</option><option value=\"HAM\">정상</option><option value=\"ALL\">전체</option></select></h3>\n" +
                "  <table id=\"evTop\"><thead><tr><th>항목</th><th>건수</th></tr></thead><tbody></tbody></table>\n" +
                "  <h3>처리 로그 <small style=\"font-weight:normal;color:#666\">(최근 200건)</small></h3>\n" +
                "  <form id=\"evSearch\" onsubmit=\"return false\">\n" +
                "    <select id=\"evAction\"><option value=\"ALL\">처리 전체</option><option value=\"HAM\">정상</option><option value=\"SPAM\">스팸</option><option value=\"REJECTED\">거부</option><option value=\"DEFERRED\">지연</option></select>\n" +
                "    <select id=\"evFilter\"><option value=\"ALL\">필터 전체</option><option>RBL</option><option>BLACKLIST</option><option>WHITELIST</option><option>GREYLIST</option><option>RULE</option><option>LLM</option><option>NONE</option></select>\n" +
                "    <input id=\"evIp\" placeholder=\"발신 IP\" size=\"14\"> <input id=\"evSender\" placeholder=\"발신자 일부\" size=\"18\">\n" +
                "    <button id=\"evFind\">검색</button> <button id=\"evCsv\" style=\"background:#16a34a\">엑셀(CSV) 내려받기</button>\n" +
                "  </form>\n" +
                "  <table id=\"evLog\"><thead><tr><th>시각</th><th>처리</th><th>필터</th><th>IP</th><th>발신자</th><th>수신 도메인</th><th>제목</th><th>점수</th><th>사유</th><th>인증</th></tr></thead><tbody></tbody></table>\n" +
                "</section>\n" +
                "<script>\n" +
                "const KEY_STORE = 'hedwigAdminKey';\n" +
                "function getKey() { try { return sessionStorage.getItem(KEY_STORE) || ''; } catch (e) { return ''; } }\n" +
                "function setKey(v) { try { if (v) sessionStorage.setItem(KEY_STORE, v); else sessionStorage.removeItem(KEY_STORE); } catch (e) {} }\n" +
                "function showAuth(msg, bad) {\n" +
                "  const el = document.getElementById('authStatus');\n" +
                "  el.textContent = msg; el.style.color = bad ? '#dc2626' : '#16a34a';\n" +
                "}\n" +
                "/** /admin/* 호출 공통 래퍼: 저장된 키를 X-Admin-Key로 붙이고, 401이면 안내를 띄운다. */\n" +
                "async function adminFetch(url, opts) {\n" +
                "  opts = opts || {};\n" +
                "  const headers = Object.assign({}, opts.headers || {});\n" +
                "  if (getKey()) headers['X-Admin-Key'] = getKey();\n" +
                "  if (window.csrfToken) headers['X-CSRF-Token'] = window.csrfToken;\n" +
                "  const res = await fetch(url, Object.assign({}, opts, { headers, credentials: 'same-origin' }));\n" +
                "  if (res.status === 401) {\n" +
                "    if (window.loginEnabled) { showLogin('세션이 없거나 만료되었습니다. 다시 로그인하세요.'); }\n" +
                "    else showAuth(getKey() ? '키가 올바르지 않습니다' : '관리자 키가 필요합니다', true);\n" +
                "  } else if (res.ok && getKey() && !window.loginEnabled) {\n" +
                "    showAuth('키 적용됨', false);\n" +
                "  }\n" +
                "  return res;\n" +
                "}\n" +
                "function showLogin(msg) {\n" +
                "  document.getElementById('sessionBar').style.display = 'none';\n" +
                "  document.getElementById('loginForm').style.display = '';\n" +
                "  const el = document.getElementById('loginStatus'); el.textContent = msg || ''; el.style.color = '#dc2626';\n" +
                "}\n" +
                "function showSession(name) {\n" +
                "  document.getElementById('loginForm').style.display = 'none';\n" +
                "  document.getElementById('sessionBar').style.display = '';\n" +
                "  document.getElementById('sessionUser').textContent = name;\n" +
                "}\n" +
                "/** 로그인 계정이 설정된 서버면 ID/PW 로그인, 아니면 기존 관리자 키 입력을 보여준다. */\n" +
                "async function initAuth() {\n" +
                "  try {\n" +
                "    const res = await fetch('/admin/session', { credentials: 'same-origin' });\n" +
                "    const st = await res.json();\n" +
                "    window.loginEnabled = !!st.loginEnabled;\n" +
                "    if (!st.loginEnabled) { document.getElementById('keyForm').style.display = ''; return true; }\n" +
                "    if (st.authenticated) { window.csrfToken = st.csrfToken; showSession(st.username); return true; }\n" +
                "    showLogin(''); return false;\n" +
                "  } catch (e) { document.getElementById('keyForm').style.display = ''; return true; }\n" +
                "}\n" +
                "async function loadStats() {\n" +
                "  const res = await fetch('/actuator/gateway');\n" +
                "  const data = await res.json();\n" +
                "  const cards = document.getElementById('cards');\n" +
                "  const items = [\n" +
                "    ['현재 커넥션', data.currentConnections],\n" +
                "    ['현재 밴 IP 수', data.currentBanCount],\n" +
                "    ['누적 밴 건수', data.totalBanEventCount],\n" +
                "    ['스팸 판정(LLM)', data.spamFilter ? data.spamFilter.spamCount : '-'],\n" +
                "    ['정상 판정(LLM)', data.spamFilter ? data.spamFilter.hamCount : '-'],\n" +
                "    ['RBL 차단', data.rbl ? data.rbl.blockedCount : '-'],\n" +
                "    ['그레이리스팅 DEFER', data.greylist ? data.greylist.deferredCount : '-'],\n" +
                "    ['그레이리스팅 ALLOW', data.greylist ? data.greylist.allowedCount : '-'],\n" +
                "    ['아웃바운드 큐', data.outbound ? data.outbound.queueCount : '-'],\n" +
                "    ['아웃바운드 실패', data.outbound ? data.outbound.failureCount : '-']\n" +
                "  ];\n" +
                "  cards.innerHTML = items.map(([label, value]) =>\n" +
                "    `<div class=\"card\"><h3>${label}</h3><div class=\"value\">${value}</div></div>`).join('');\n" +
                "}\n" +
                "async function loadList() {\n" +
                "  const res = await adminFetch('/admin/mail-list');\n" +
                "  if (!res.ok) { document.querySelector('#listTable tbody').innerHTML = ''; return; }\n" +
                "  const rows = await res.json();\n" +
                "  const tbody = document.querySelector('#listTable tbody');\n" +
                "  tbody.innerHTML = rows.map(r => `<tr>\n" +
                "    <td>${r.listType}</td><td>${r.pattern}</td><td>${r.recipient || '(전역)'}</td><td>${r.reason || ''}</td>\n" +
                "    <td><button class=\"danger\" onclick=\"removeEntry('${r.listType}','${r.pattern}','${r.recipient}')\">삭제</button></td>\n" +
                "  </tr>`).join('');\n" +
                "}\n" +
                "async function removeEntry(listType, pattern, recipient) {\n" +
                "  await adminFetch(`/admin/mail-list?listType=${encodeURIComponent(listType)}&pattern=${encodeURIComponent(pattern)}&recipient=${encodeURIComponent(recipient)}`, { method: 'DELETE' });\n" +
                "  loadList();\n" +
                "}\n" +
                "document.getElementById('addForm').addEventListener('submit', async (e) => {\n" +
                "  e.preventDefault();\n" +
                "  await adminFetch('/admin/mail-list', {\n" +
                "    method: 'POST', headers: { 'Content-Type': 'application/json' },\n" +
                "    body: JSON.stringify({\n" +
                "      listType: document.getElementById('listType').value,\n" +
                "      pattern: document.getElementById('pattern').value,\n" +
                "      recipient: document.getElementById('recipient').value,\n" +
                "      reason: document.getElementById('reason').value\n" +
                "    })\n" +
                "  });\n" +
                "  document.getElementById('pattern').value = '';\n" +
                "  document.getElementById('recipient').value = '';\n" +
                "  document.getElementById('reason').value = '';\n" +
                "  loadList();\n" +
                "});\n" +
                "async function loadRules() {\n" +
                "  const res = await adminFetch('/admin/spam-rules');\n" +
                "  if (!res.ok) { document.querySelector('#ruleTable tbody').innerHTML = ''; return; }\n" +
                "  const rows = await res.json();\n" +
                "  ruleById = {}; rows.forEach(r => { ruleById[r.id] = r; });\n" +
                "  const stats = {};\n" +
                "  try { const sr = await adminFetch('/admin/spam-rules/stats'); if (sr.ok) (await sr.json()).forEach(x => { stats[x.ruleId] = x; }); } catch (e) {}\n" +
                "  const tbody = document.querySelector('#ruleTable tbody');\n" +
                "  tbody.innerHTML = rows.map(r => `<tr>\n" +
                "    <td>${r.id}</td><td>${r.ruleType}</td><td>${escapeHtml(r.pattern)}</td><td>${r.weight}</td>\n" +
                "    <td>${stats[r.id] ? stats[r.id].hits + ' (' + stats[r.id].spamHits + ')' : '0'}</td>\n" +
                "    <td>${r.enabled ? '활성' : '비활성'}</td><td>${r.reason || ''}</td>\n" +
                "    <td>\n" +
                "      <button onclick=\"toggleRule(${r.id}, '${r.ruleType}', '${encodeAttr(r.pattern)}', ${r.weight}, ${!r.enabled}, '${encodeAttr(r.reason || '')}')\">${r.enabled ? '비활성화' : '활성화'}</button>\n" +
                "      <button onclick=\"analyzeRule(${r.id})\">분석</button>\n" +
                "      <button class=\"danger\" onclick=\"removeRule(${r.id})\">삭제</button>\n" +
                "    </td>\n" +
                "  </tr>`).join('');\n" +
                "}\n" +
                "function escapeHtml(s) { const d = document.createElement('div'); d.innerText = s == null ? '' : s; return d.innerHTML; }\n" +
                "function encodeAttr(s) { return (s == null ? '' : s).replace(/'/g, \"\\\\'\"); }\n" +
                "let ruleById = {};\n" +
                "const TAB_STORE = 'hedwigDashTab';\n" +
                "const CHAT_SAMPLES = ['어제 스팸 몇 건이야?', '최근 7일 스팸 상위 도메인 5개', '이번 주 일별 추이', '적중 많은 룰 알려줘', '검토 대기 신고 있어?'];\n" +
                "function chatAdd(cls, html) {\n" +
                "  const log = document.getElementById('chatLog'); const d = document.createElement('div');\n" +
                "  d.style.cssText = 'margin:0.4rem 0;padding:0.5rem 0.7rem;border-radius:8px;max-width:92%;' + (cls === 'me' ? 'background:#dbeafe;margin-left:auto;width:fit-content' : 'background:#f3f4f6');\n" +
                "  d.innerHTML = html; log.appendChild(d); log.scrollTop = log.scrollHeight; return d;\n" +
                "}\n" +
                "async function chatAsk(text) {\n" +
                "  text = (text || '').trim(); if (!text) return;\n" +
                "  document.getElementById('chatInput').value = '';\n" +
                "  chatAdd('me', escapeHtml(text));\n" +
                "  const wait = chatAdd('bot', '생각하는 중... (첫 질문은 모델을 불러오느라 수십 초 걸릴 수 있습니다)');\n" +
                "  try {\n" +
                "    const res = await adminFetch('/admin/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message: text }) });\n" +
                "    const b = await res.json();\n" +
                "    if (!res.ok) { wait.innerHTML = '<span style=\"color:#dc2626\">' + escapeHtml(b.error || '답변하지 못했습니다') + '</span>'; return; }\n" +
                "    let h = escapeHtml(b.answer || '');\n" +
                "    if (b.rows && b.rows.length) {\n" +
                "      h += '<table style=\"margin-top:0.4rem;width:auto\"><thead><tr>' + b.columns.map(c => '<th>' + escapeHtml(c) + '</th>').join('') + '</tr></thead><tbody>' +\n" +
                "        b.rows.map(r => '<tr>' + r.map(v => '<td>' + escapeHtml(String(v)) + '</td>').join('') + '</tr>').join('') + '</tbody></table>';\n" +
                "    }\n" +
                "    if (b.proposal) {\n" +
                "      const pr = b.proposal;\n" +
                "      h += '<div style=\"margin-top:0.5rem;border:2px solid #f59e0b;border-radius:8px;padding:0.6rem;background:#fffbeb\"><b>변경 제안</b><br>' +\n" +
                "        (pr.before ? '현재: ' + escapeHtml(pr.before) + '<br>' : '') + '변경: <b>' + escapeHtml(pr.after) + '</b>' +\n" +
                "        (pr.warn ? '<br><span style=\"color:#b45309\">⚠ ' + escapeHtml(pr.warn) + '</span>' : '') +\n" +
                "        '<br><button type=\"button\" class=\"pr-apply\" data-id=\"' + pr.id + '\">적용</button> <button type=\"button\" class=\"pr-cancel\" style=\"background:#6b7280\" data-id=\"' + pr.id + '\">취소</button></div>';\n" +
                "    }\n" +
                "    wait.innerHTML = h;\n" +
                "  } catch (e) { wait.innerHTML = '<span style=\"color:#dc2626\">요청에 실패했습니다</span>'; }\n" +
                "}\n" +
                "document.getElementById('chatSamples').innerHTML = CHAT_SAMPLES.map(s => '<button type=\"button\" class=\"chip\" style=\"background:#e5e7eb;color:#111;margin:0 0.3rem 0.3rem 0\">' + escapeHtml(s) + '</button>').join('');\n" +
                "document.getElementById('chatSamples').addEventListener('click', (e) => { const b = e.target.closest('.chip'); if (b) chatAsk(b.textContent); });\n" +
                "document.getElementById('chatLog').addEventListener('click', async (e) => {\n" +
                "  const ap = e.target.closest('.pr-apply'), ca = e.target.closest('.pr-cancel');\n" +
                "  const btn = ap || ca; if (!btn) return;\n" +
                "  const box = btn.parentElement; box.querySelectorAll('button').forEach(x => x.disabled = true);\n" +
                "  const res = await adminFetch('/admin/chat/proposals/' + btn.dataset.id + (ap ? '/apply' : '/cancel'), { method: 'POST' });\n" +
                "  let msg = ap ? '' : '제안을 취소했습니다.';\n" +
                "  if (ap) { const b = await res.json(); msg = res.ok ? b.message : '적용하지 못했습니다: ' + b.error; }\n" +
                "  box.innerHTML = '<b>' + (ap && res.ok ? '✔ ' : '') + escapeHtml(msg) + '</b>';\n" +
                "  if (ap && res.ok) { loadRules(); loadAudit(); }\n" +
                "});\n" +
                "async function loadAudit() {\n" +
                "  const res = await adminFetch('/admin/audit?limit=15'); if (!res.ok) return;\n" +
                "  const rows = await res.json();\n" +
                "  document.querySelector('#auditTable tbody').innerHTML = rows.map(x => '<tr><td>' + x.at + '</td><td>' + escapeHtml(x.actor) + '</td><td>' + escapeHtml(x.source) + '</td><td>' + escapeHtml(x.action) + '</td><td>' + escapeHtml(x.target) + '</td><td>' + escapeHtml(x.before || '') + '</td><td>' + escapeHtml(x.after || '') + '</td></tr>').join('') || '<tr><td colspan=\"7\">변경 이력 없음</td></tr>';\n" +
                "}\n" +
                "document.getElementById('auditReload').addEventListener('click', loadAudit);\n" +
                "document.getElementById('chatSend').addEventListener('click', () => chatAsk(document.getElementById('chatInput').value));\n" +
                "document.getElementById('chatInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); chatAsk(e.target.value); } });\n" +
                "const EV_LABEL = { HAM: '정상', SPAM: '스팸', REJECTED: '거부', DEFERRED: '지연' };\n" +
                "function evRange() {\n" +
                "  const per = document.getElementById('evPeriod').value;\n" +
                "  if (per === 'custom') {\n" +
                "    const f = document.getElementById('evFrom').value, t = document.getElementById('evTo').value;\n" +
                "    return f && t ? 'from=' + f + '&to=' + t : 'period=last7d';\n" +
                "  }\n" +
                "  return 'period=' + per;\n" +
                "}\n" +
                "async function loadEventStats() {\n" +
                "  const r = evRange();\n" +
                "  const sr = await adminFetch('/admin/stats/summary?' + r);\n" +
                "  if (!sr.ok) return;\n" +
                "  const s = await sr.json();\n" +
                "  document.getElementById('evCards').innerHTML = s.enabled\n" +
                "    ? [['전체', s.total], ['정상', s.HAM], ['스팸', s.SPAM], ['거부', s.REJECTED], ['지연', s.DEFERRED]].map(c => '<div class=\"card\"><h3>' + c[0] + '</h3><div class=\"value\">' + c[1] + '</div></div>').join('') + '<div class=\"card\"><h3>기간</h3><div>' + s.from + ' ~ ' + s.to + '</div></div>'\n" +
                "    : '<div class=\"card\">이벤트 로그가 꺼져 있습니다(gateway.event-log.enabled)</div>';\n" +
                "  const dr = await adminFetch('/admin/stats/daily?' + r);\n" +
                "  if (dr.ok) {\n" +
                "    const rows = await dr.json();\n" +
                "    document.querySelector('#evDaily tbody').innerHTML = rows.map(x => {\n" +
                "      const tot = x.HAM + x.SPAM + x.REJECTED + x.DEFERRED; const pct = tot ? Math.round(100 * x.SPAM / tot) : 0;\n" +
                "      return '<tr><td>' + x.day + '</td><td>' + x.HAM + '</td><td>' + x.SPAM + '</td><td>' + x.REJECTED + '</td><td>' + x.DEFERRED + '</td><td><div style=\"background:#e5e7eb;border-radius:4px;width:160px\"><div style=\"background:#dc2626;height:10px;border-radius:4px;width:' + (pct * 1.6) + 'px\"></div></div> ' + pct + '%</td></tr>';\n" +
                "    }).join('') || '<tr><td colspan=\"6\">데이터 없음</td></tr>';\n" +
                "  }\n" +
                "  const ar = await adminFetch('/admin/stats/auth?' + r);\n" +
                "  if (ar.ok) {\n" +
                "    const a = await ar.json(); const get = (m, k) => (a[m] && a[m][k]) || 0;\n" +
                "    document.querySelector('#evAuth tbody').innerHTML = ['pass', 'fail', 'softfail', 'neutral', 'none', 'temperror', 'permerror']\n" +
                "      .filter(k => get('spf', k) + get('dkim', k) + get('dmarc', k) > 0)\n" +
                "      .map(k => '<tr><td>' + k + '</td><td>' + get('spf', k) + '</td><td>' + get('dkim', k) + '</td><td>' + get('dmarc', k) + '</td></tr>').join('') || '<tr><td colspan=\"4\">인증 결과가 기록된 메일이 없습니다</td></tr>';\n" +
                "  }\n" +
                "  loadEventTop(); loadEventLog();\n" +
                "}\n" +
                "async function loadEventTop() {\n" +
                "  const dim = document.getElementById('evDim').value, act = document.getElementById('evTopAction').value;\n" +
                "  const res = await adminFetch('/admin/stats/top?' + evRange() + '&dimension=' + dim + '&action=' + act + '&limit=10');\n" +
                "  if (!res.ok) return;\n" +
                "  const rows = await res.json();\n" +
                "  document.querySelector('#evTop tbody').innerHTML = rows.map(x => '<tr><td>' + escapeHtml(x.key) + '</td><td>' + x.count + '</td></tr>').join('') || '<tr><td colspan=\"2\">데이터 없음</td></tr>';\n" +
                "}\n" +
                "function evLogQuery() {\n" +
                "  const q = evRange() + '&action=' + document.getElementById('evAction').value + '&filter=' + document.getElementById('evFilter').value;\n" +
                "  const ip = document.getElementById('evIp').value.trim(), sd = document.getElementById('evSender').value.trim();\n" +
                "  return q + (ip ? '&ip=' + encodeURIComponent(ip) : '') + (sd ? '&sender=' + encodeURIComponent(sd) : '');\n" +
                "}\n" +
                "async function loadEventLog() {\n" +
                "  const res = await adminFetch('/admin/events?' + evLogQuery() + '&limit=200');\n" +
                "  if (!res.ok) return;\n" +
                "  const rows = await res.json();\n" +
                "  document.querySelector('#evLog tbody').innerHTML = rows.map(x => '<tr><td>' + x.at + '</td><td>' + (EV_LABEL[x.action] || x.action) + '</td><td>' + x.filter + '</td><td>' + escapeHtml(x.ip) + '</td><td>' + escapeHtml(x.from) + '</td><td>' + escapeHtml(x.rcptDomain) + '</td><td>' + escapeHtml(x.subject) + '</td><td>' + x.score + '</td><td>' + escapeHtml(x.reason) + '</td><td>' + escapeHtml(x.auth || '') + '</td></tr>').join('') || '<tr><td colspan=\"10\">데이터 없음</td></tr>';\n" +
                "}\n" +
                "document.getElementById('evReload').addEventListener('click', loadEventStats);\n" +
                "document.getElementById('evPeriod').addEventListener('change', loadEventStats);\n" +
                "document.getElementById('evDim').addEventListener('change', loadEventTop);\n" +
                "document.getElementById('evTopAction').addEventListener('change', loadEventTop);\n" +
                "document.getElementById('evFind').addEventListener('click', loadEventLog);\n" +
                "document.getElementById('evCsv').addEventListener('click', async () => {\n" +
                "  const res = await adminFetch('/admin/events/export.csv?' + evLogQuery());\n" +
                "  if (!res.ok) { alert('내려받지 못했습니다'); return; }\n" +
                "  const blob = await res.blob(); const a = document.createElement('a');\n" +
                "  a.href = URL.createObjectURL(blob); a.download = 'mail-events.csv'; document.body.appendChild(a); a.click(); a.remove();\n" +
                "});\n" +
                "document.getElementById('tabs').addEventListener('click', (e) => {\n" +
                "  const b = e.target.closest('.tab'); if (b && b.dataset.tab === 'events') loadEventStats();\n" +
                "  if (b && b.dataset.tab === 'chat') loadAudit();\n" +
                "});\n" +
                "function showTab(name) {\n" +
                "  if (!document.getElementById('tab-' + name)) name = 'rules';\n" +
                "  document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('active', p.id === 'tab-' + name));\n" +
                "  document.querySelectorAll('#tabs .tab').forEach(b => b.classList.toggle('active', b.dataset.tab === name));\n" +
                "  try { sessionStorage.setItem(TAB_STORE, name); } catch (e) {}\n" +
                "}\n" +
                "document.getElementById('tabs').addEventListener('click', (e) => {\n" +
                "  const b = e.target.closest('.tab'); if (b) showTab(b.dataset.tab);\n" +
                "});\n" +
                "/** 룰 적중 샘플을 보여주고 LLM 조언을 받아 패널에 표시한다. 적용은 관리자가 버튼으로 직접 한다. */\n" +
                "async function analyzeRule(id) {\n" +
                "  const overlay = document.getElementById('advicePanel'); const panel = document.getElementById('adviceBox');\n" +
                "  const r = ruleById[id];\n" +
                "  overlay.style.display = 'flex'; overlay.onclick = (e) => { if (e.target === overlay) overlay.style.display = 'none'; };\n" +
                "  panel.innerHTML = '분석 중... (LLM 호출, 수 초 걸릴 수 있음)';\n" +
                "  let html = `<b>룰 #${id}</b> ${escapeHtml(r.pattern)} (현재 가중치 ${r.weight})<br>`;\n" +
                "  const sres = await adminFetch(`/admin/spam-rules/${id}/samples`);\n" +
                "  if (sres.ok) {\n" +
                "    const samples = await sres.json();\n" +
                "    html += '<details><summary>적중 샘플 ' + samples.length + '건(마스킹됨)</summary><ul>' + samples.map(s => `<li>[${s.spamVerdict ? '스팸' : '정상'}] ${escapeHtml(s.fromDomain)} | ${escapeHtml(s.subject)} | ${escapeHtml(s.snippet)}</li>`).join('') + '</ul></details>';\n" +
                "  }\n" +
                "  const ares = await adminFetch(`/admin/spam-rules/${id}/advice`, { method: 'POST' });\n" +
                "  const body = await ares.json();\n" +
                "  if (!ares.ok) { panel.innerHTML = html + `<p style=\"color:#dc2626\">${escapeHtml(body.error || '조언을 받지 못했습니다')}</p><button onclick=\"document.getElementById('advicePanel').style.display='none'\">닫기</button>`; return; }\n" +
                "  const actionKo = { KEEP: '유지', RAISE: '상향', LOWER: '하향', DISABLE: '비활성화' }[body.action] || body.action;\n" +
                "  html += `<p><b>LLM 조언(참고용)</b>: ${actionKo}` + (body.recommendedWeight != null ? ` → 권장 가중치 ${body.recommendedWeight}` : '') + ` (신뢰도 ${body.confidence}, 적중 ${body.hits}건)<br>${escapeHtml(body.rationale)}</p>`;\n" +
                "  html += '<button id=\"applyAdvice\">권장 가중치 적용</button> <button id=\"closeAdvice\" style=\"background:#6b7280\">닫기</button>';\n" +
                "  panel.innerHTML = html;\n" +
                "  document.getElementById('closeAdvice').onclick = () => { overlay.style.display = 'none'; };\n" +
                "  document.getElementById('applyAdvice').onclick = async () => {\n" +
                "    const w = body.action === 'DISABLE' ? r.weight : body.recommendedWeight;\n" +
                "    if (w == null) { alert('권장 가중치가 없습니다'); return; }\n" +
                "    if (!confirm(`룰 #${id}를 ` + (body.action === 'DISABLE' ? '비활성화' : `가중치 ${w}로 변경`) + '합니다. 진행할까요?')) return;\n" +
                "    await toggleRule(id, r.ruleType, r.pattern, w, body.action === 'DISABLE' ? false : r.enabled, r.reason || '');\n" +
                "    overlay.style.display = 'none';\n" +
                "  };\n" +
                "}\n" +
                "async function loadReportSettings() {\n" +
                "  const res = await adminFetch('/admin/spam-reports/settings');\n" +
                "  if (!res.ok) return;\n" +
                "  const st = await res.json();\n" +
                "  document.getElementById('rsAuto').checked = !!st.autoProcess;\n" +
                "  document.getElementById('rsConf').value = st.autoMinConfidence;\n" +
                "  document.getElementById('rsW').value = st.autoMaxWeight;\n" +
                "  document.getElementById('rsMax').value = st.autoMaxPerDay;\n" +
                "  document.getElementById('rsHam').checked = !!st.autoDismissHam;\n" +
                "  window.rsLoaded = true;\n" +
                "}\n" +
                "document.getElementById('rsSave').addEventListener('click', async () => {\n" +
                "  const auto = document.getElementById('rsAuto').checked;\n" +
                "  if (auto && !confirm('자동 처리를 켜면 LLM 판정만으로 룰이 추가됩니다(관리자 승인 없음). 진행할까요?')) return;\n" +
                "  const body = { autoProcess: auto, autoMinConfidence: parseFloat(document.getElementById('rsConf').value),\n" +
                "    autoMaxWeight: parseFloat(document.getElementById('rsW').value), autoMaxPerDay: parseInt(document.getElementById('rsMax').value, 10),\n" +
                "    autoDismissHam: document.getElementById('rsHam').checked };\n" +
                "  const res = await adminFetch('/admin/spam-reports/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });\n" +
                "  const el = document.getElementById('rsStatus');\n" +
                "  el.textContent = res.ok ? '저장됨' : '저장 실패'; el.style.color = res.ok ? '#16a34a' : '#dc2626';\n" +
                "  if (res.ok) loadReportSettings();\n" +
                "});\n" +
                "async function loadReports() {\n" +
                "  if (!window.rsLoaded) await loadReportSettings();\n" +
                "  const tbody = document.querySelector('#reportTable tbody');\n" +
                "  const res = await adminFetch('/admin/spam-reports');\n" +
                "  if (!res.ok) { tbody.innerHTML = ''; return; }\n" +
                "  const rows = await res.json();\n" +
                "  window.reportById = {}; rows.forEach(r => { window.reportById[r.id] = r; });\n" +
                "  const waiting = rows.filter(r => r.status === 'ANALYZED').length;\n" +
                "  document.getElementById('badgeReports').textContent = waiting ? ' (' + waiting + ')' : '';\n" +
                "  const vk = { SPAM: '스팸', HAM: '정상', UNSURE: '불확실' };\n" +
                "  tbody.innerHTML = rows.map(r => `<tr>\n" +
                "    <td>${r.id}</td><td>${escapeHtml(r.reporter)}</td><td>${escapeHtml(r.fromDomain)}</td>\n" +
                "    <td><b>${escapeHtml(r.subject)}</b><br><span style=\"color:#555\">${escapeHtml((r.snippet || '').substring(0, 120))}</span>${r.reporterReason ? '<br><span style=\"color:#b45309\">신고 사유: ' + escapeHtml(r.reporterReason) + '</span>' : ''}</td>\n" +
                "    <td>${r.status === 'PENDING' ? '분석 중' : (vk[r.verdict] || '-') + ' (' + Math.round((r.score || 0) * 100) + '%)'}</td>\n" +
                "    <td>${escapeHtml(r.reason || '')}</td>\n" +
                "    <td>${r.suggestedPattern ? '<code>' + escapeHtml(r.suggestedPattern) + '</code> (' + r.suggestedWeight + ')' : '-'}</td>\n" +
                "    <td>${{ PENDING: '분석 중', ANALYZED: '검토 대기', RULE_APPROVED: '룰 추가됨', DISMISSED: '기각', AUTO_APPROVED: '자동 룰 추가', AUTO_DISMISSED: '자동 기각' }[r.status] || r.status}</td>\n" +
                "    <td>${r.ragAdded ? '사용 중' : '-'}</td>\n" +
                "    <td>\n" +
                "      <button data-act=\"approve\" data-id=\"${r.id}\">룰 추가</button>\n" +
                "      ${r.hasEml ? '<button data-act=\"eml\" data-id=\"' + r.id + '\">EML 다운로드</button>' : ''}\n" +
                "      <button data-act=\"reanalyze\" data-id=\"${r.id}\">LLM 재분석</button>\n" +
                "      <button data-act=\"rag\" data-id=\"${r.id}\">${r.ragAdded ? 'RAG 제외' : 'RAG 추가'}</button>\n" +
                "      <button class=\"danger\" data-act=\"dismiss\" data-id=\"${r.id}\">기각</button>\n" +
                "    </td>\n" +
                "  </tr>`).join('');\n" +
                "}\n" +
                "document.querySelector('#reportTable tbody').addEventListener('click', async (e) => {\n" +
                "  const btn = e.target.closest('button[data-act]'); if (!btn) return;\n" +
                "  const id = btn.dataset.id; const r = window.reportById[id];\n" +
                "  if (btn.dataset.act === 'approve') {\n" +
                "    const pattern = prompt('추가할 키워드 룰(정규식). LLM 제안값을 고치거나 직접 입력하세요.', r.suggestedPattern || '');\n" +
                "    if (!pattern) return;\n" +
                "    const w = parseFloat(prompt('가중치(임계치 5 기준, 단독으로 스팸 처리하려면 5 이상)', r.suggestedWeight > 0 ? r.suggestedWeight : 2));\n" +
                "    if (isNaN(w)) return;\n" +
                "    const res = await adminFetch(`/admin/spam-reports/${id}/approve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pattern, weight: w }) });\n" +
                "    if (!res.ok) { const b = await res.json(); alert(b.error || '룰을 추가하지 못했습니다'); return; }\n" +
                "    loadRules();\n" +
                "  } else if (btn.dataset.act === 'eml') {\n" +
                "    const eres = await adminFetch(`/admin/spam-reports/${id}/eml`);\n" +
                "    if (!eres.ok) { alert('메일 원본(EML)을 내려받지 못했습니다'); return; }\n" +
                "    const blob = await eres.blob();\n" +
                "    const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'spam-report-' + id + '.eml';\n" +
                "    document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(link.href), 1000);\n" +
                "  } else if (btn.dataset.act === 'reanalyze') {\n" +
                "    btn.disabled = true; btn.textContent = '분석 중...';\n" +
                "    const res = await adminFetch(`/admin/spam-reports/${id}/reanalyze`, { method: 'POST' });\n" +
                "    if (!res.ok) { const b = await res.json().catch(() => ({})); alert(b.error || 'LLM 재분석에 실패했습니다'); }\n" +
                "  } else if (btn.dataset.act === 'rag') {\n" +
                "    await adminFetch(`/admin/spam-reports/${id}/rag`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ on: !r.ragAdded }) });\n" +
                "  } else if (btn.dataset.act === 'dismiss') {\n" +
                "    if (!confirm('이 신고를 기각합니다(RAG 사례에서도 제외됩니다). 진행할까요?')) return;\n" +
                "    await adminFetch(`/admin/spam-reports/${id}/dismiss`, { method: 'POST' });\n" +
                "  }\n" +
                "  loadReports();\n" +
                "});\n" +
                "async function toggleRule(id, ruleType, pattern, weight, enabled, reason) {\n" +
                "  await adminFetch(`/admin/spam-rules/${id}`, {\n" +
                "    method: 'PUT', headers: { 'Content-Type': 'application/json' },\n" +
                "    body: JSON.stringify({ ruleType, pattern, weight, enabled, reason })\n" +
                "  });\n" +
                "  loadRules();\n" +
                "}\n" +
                "async function removeRule(id) {\n" +
                "  await adminFetch(`/admin/spam-rules/${id}`, { method: 'DELETE' });\n" +
                "  loadRules();\n" +
                "}\n" +
                "document.getElementById('ruleAddForm').addEventListener('submit', async (e) => {\n" +
                "  e.preventDefault();\n" +
                "  await adminFetch('/admin/spam-rules', {\n" +
                "    method: 'POST', headers: { 'Content-Type': 'application/json' },\n" +
                "    body: JSON.stringify({\n" +
                "      ruleType: document.getElementById('ruleType').value,\n" +
                "      pattern: document.getElementById('rulePattern').value,\n" +
                "      weight: parseFloat(document.getElementById('ruleWeight').value || '1.0'),\n" +
                "      reason: document.getElementById('ruleReason').value\n" +
                "    })\n" +
                "  });\n" +
                "  document.getElementById('rulePattern').value = '';\n" +
                "  document.getElementById('ruleReason').value = '';\n" +
                "  loadRules();\n" +
                "});\n" +
                "document.getElementById('keyForm').addEventListener('submit', (e) => {\n" +
                "  e.preventDefault();\n" +
                "  setKey(document.getElementById('adminKey').value.trim());\n" +
                "  showAuth('', false); loadList(); loadRules(); loadReports();\n" +
                "});\n" +
                "document.getElementById('keyClear').addEventListener('click', () => {\n" +
                "  setKey(''); document.getElementById('adminKey').value = '';\n" +
                "  showAuth('키를 지웠습니다', false); loadList(); loadRules(); loadReports();\n" +
                "});\n" +
                "document.getElementById('loginForm').addEventListener('submit', async (e) => {\n" +
                "  e.preventDefault();\n" +
                "  const body = { username: document.getElementById('loginUser').value.trim(), password: document.getElementById('loginPass').value };\n" +
                "  const res = await fetch('/admin/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), credentials: 'same-origin' });\n" +
                "  const data = await res.json().catch(() => ({}));\n" +
                "  if (!res.ok) { showLogin(data.error || '로그인하지 못했습니다'); return; }\n" +
                "  document.getElementById('loginPass').value = '';\n" +
                "  window.csrfToken = data.csrfToken; showSession(data.username);\n" +
                "  loadList(); loadRules(); loadReports();\n" +
                "});\n" +
                "document.getElementById('logoutBtn').addEventListener('click', async () => {\n" +
                "  await fetch('/admin/logout', { method: 'POST', headers: { 'X-CSRF-Token': window.csrfToken || '' }, credentials: 'same-origin' });\n" +
                "  window.csrfToken = ''; location.reload();\n" +
                "});\n" +
                "document.getElementById('adminKey').value = getKey();\n" +
                "let savedTab = 'rules'; try { savedTab = sessionStorage.getItem(TAB_STORE) || 'rules'; } catch (e) {}\n" +
                "showTab(savedTab);\n" +
                "loadStats();\n" +
                "initAuth().then((ok) => { if (ok) { loadList(); loadRules(); loadReports(); if (savedTab === 'events') loadEventStats(); } });\n" +
                "setInterval(loadStats, 5000);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>\n";
    }
}
