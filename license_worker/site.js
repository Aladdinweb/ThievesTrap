// Public website for Thieves Trap, served by the license Worker (landing, Terms, Privacy).
// Interactive layout: sidebar menu, one panel at a time (hash navigation), tabs and accordions.
// Static, no admin links and no repository references. Inline CSS/JS run under a per-request nonce;
// the page makes no external requests (only GET /version on its own origin).

const SUPPORT_URL = "https://www.facebook.com/share/1D2RrP4ey2/";
const MESSENGER_URL = "https://m.me/61576473907901";
const UPDATED = { en: "10 November 2026", ar: "10 نوفمبر 2026" };

const SHIELD_PATHS = '<path fill="#000000" fill-opacity="0.20" d="M54,97C54,97 16,80 16,55V30L54,16L92,30V55C92,80 54,97 54,97Z" transform="translate(2 3)"/><path fill="#1A1A1A" d="M54,94C54,94 18,77 18,53V31L54,18L90,31V53C90,77 54,94 54,94Z"/><path fill="#2D2D2D" d="M54,91C54,91 21,75 21,52V33L54,21L87,33V52C87,75 54,91 54,91Z"/><path fill="#8B0000" d="M54,89C54,89 23,73 23,51V34L54,22L85,34V51C85,73 54,89 54,89Z"/><path fill="#CC0000" d="M54,86C54,86 25,71 25,50V36L54,25L83,36V50C83,71 54,86 54,86Z"/><path fill="#FF2222" d="M54,25L83,36V44C83,44 68,38 54,38C40,38 25,44 25,44V36Z"/><path fill="#FFFFFF" fill-opacity="0.13" d="M54,26L80,36V42C72,39 63,37 54,37C45,37 36,39 28,42V36Z"/><path fill="#AA0000" d="M54,82C54,82 29,68 29,49V39L54,29L79,39V49C79,68 54,82 54,82Z"/><path fill="#FF4444" fill-opacity="0.27" d="M54,29L79,39V43C71,40 63,38 54,38C45,38 37,40 29,43V39Z"/><path fill="#FF0000" fill-opacity="0.53" d="M25,36L25,51C25,60 29,68 34,74L34,36Z"/><path fill="#FF0000" fill-opacity="0.33" d="M83,36L83,51C83,60 79,68 74,74L74,36Z"/><path fill="#FFFFFF" d="M37,54L44,62L70,36L76,42L44,74L31,60Z"/><path fill="#000000" fill-opacity="0.20" d="M38,56L44,63L70,38L75,43L44,75L32,62Z"/><path fill="#FFFFFF" fill-opacity="0.10" d="M42,26C46,24 50,23 54,23C58,23 62,24 66,26L54,22Z"/>';
const SHIELD = '<svg viewBox="0 0 108 108" role="img" aria-label="Thieves Trap">' + SHIELD_PATHS + '</svg>';
const FAVICON = "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">' + SHIELD_PATHS + '</svg>');

// Bilingual helpers: one element per language, CSS shows the active one.
const bi = (tag, en, ar, cls = "") =>
  `<${tag} class="en ${cls}">${en}</${tag}><${tag} class="ar ${cls}" lang="ar" dir="rtl">${ar}</${tag}>`;
const sp = (en, ar) => `<span class="en">${en}</span><span class="ar" lang="ar" dir="rtl">${ar}</span>`;

const ICON = {
  home: '<path d="M3 11.5 12 4l9 7.5M5.5 10v9.5h13V10"/>',
  features: '<path d="M12 3 4 6v6c0 4.5 3.2 7.8 8 9 4.8-1.2 8-4.5 8-9V6z"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
  plans: '<path d="M3 12V4h8l10 10-8 8z"/><circle cx="7.5" cy="8.5" r="1.3"/>',
  install: '<path d="M12 3v12m-5-5 5 5 5-5M4 20h16"/>',
  support: '<path d="M4 5h16v11H9l-5 4z"/>',
  terms: '<path d="M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h7"/>',
  privacy: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
};
const ico = (n) => `<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[n]}</svg>`;

const CSS = `
:root{--bg:#050505;--card:#0d0d10;--card2:#121217;--line:#202027;--tx:#f4f4f5;--mu:#a0a0a8;--red:#cc0000;--red2:#ff2a2a;--grn:#00ff66;--side:250px}
*{box-sizing:border-box}
html{scroll-behavior:smooth;-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--tx);font:16px/1.65 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Arabic",Tahoma,sans-serif;min-height:100vh;display:flex;flex-direction:column;overflow-x:hidden}
html[data-l="en"] .ar,html[data-l="ar"] .en{display:none!important}
a{color:inherit;text-decoration:none}
button{font:inherit;color:inherit}
.spot{position:fixed;inset:0;pointer-events:none;z-index:0;background:radial-gradient(520px circle at var(--px,70%) var(--py,20%),rgba(0,255,102,.07),transparent 60%),radial-gradient(700px circle at calc(100% - var(--px,70%)) calc(100% - var(--py,20%)),rgba(204,0,0,.07),transparent 60%)}
.shell{position:relative;z-index:1;flex:1;display:grid;grid-template-columns:var(--side) 1fr}
.side{position:sticky;top:0;height:100vh;display:flex;flex-direction:column;gap:18px;padding:20px 14px;background:rgba(10,10,13,.78);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);border-inline-end:1px solid var(--line)}
.brand{display:flex;align-items:center;gap:10px;font-weight:800;letter-spacing:.06em;padding:4px 8px}
.brand svg{width:36px;height:36px;filter:drop-shadow(0 0 10px rgba(204,0,0,.5))}
.menu{display:flex;flex-direction:column;gap:4px;margin-top:6px}
.menu hr{border:0;border-top:1px solid var(--line);width:100%;margin:10px 0}
.menu a{position:relative;display:flex;align-items:center;gap:12px;padding:11px 14px;border-radius:12px;color:var(--mu);font-weight:600;overflow:hidden;transition:color .2s,background .2s,transform .2s}
.menu a .ico{width:20px;height:20px;flex:none}
.menu a:hover{color:var(--tx);background:rgba(255,255,255,.05);transform:translateX(3px)}
html[dir="rtl"] .menu a:hover{transform:translateX(-3px)}
.menu a.on{color:var(--tx);background:linear-gradient(90deg,rgba(0,255,102,.14),rgba(0,255,102,0))}
html[dir="rtl"] .menu a.on{background:linear-gradient(270deg,rgba(0,255,102,.14),rgba(0,255,102,0))}
.menu a.on::before{content:"";position:absolute;inset-block:8px;inset-inline-start:0;width:3px;border-radius:3px;background:var(--grn);box-shadow:0 0 12px var(--grn)}
.menu a.on .ico{color:var(--grn)}
.sfoot{margin-top:auto;display:flex;flex-direction:column;gap:10px;padding:0 6px}
.chipv{font-size:12px;color:var(--mu)}
.lang{display:flex;gap:4px}
.lang button{background:var(--card);color:var(--mu);border:1px solid var(--line);border-radius:8px;padding:6px 12px;font-weight:600;font-size:13px;cursor:pointer;transition:all .2s}
.lang button:hover{border-color:#3a3a44;color:var(--tx)}
.lang button[aria-pressed="true"]{color:#03140a;background:var(--grn);border-color:var(--grn);box-shadow:0 0 14px rgba(0,255,102,.45)}
.mbar{display:none}
.scrim{display:none}
.main{padding:34px clamp(18px,4vw,48px) 30px;max-width:980px;width:100%;margin-inline:auto}
.panel{display:block}
html.js .panel{display:none}
html.js .panel.on{display:block;animation:rise .5s cubic-bezier(.2,.8,.2,1)}
@keyframes rise{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}
.panel.on .st{animation:rise .6s cubic-bezier(.2,.8,.2,1) both}
.panel.on .st:nth-child(1){animation-delay:.03s}.panel.on .st:nth-child(2){animation-delay:.09s}.panel.on .st:nth-child(3){animation-delay:.15s}.panel.on .st:nth-child(4){animation-delay:.21s}.panel.on .st:nth-child(5){animation-delay:.27s}.panel.on .st:nth-child(6){animation-delay:.33s}.panel.on .st:nth-child(7){animation-delay:.39s}.panel.on .st:nth-child(8){animation-delay:.45s}
.hero{display:grid;gap:28px;grid-template-columns:1.2fr .8fr;align-items:center;padding:14px 0 10px}
.eyebrow{color:var(--grn);font-weight:700;font-size:12px;letter-spacing:.14em;text-transform:uppercase}
h1{font-size:clamp(32px,5.4vw,54px);line-height:1.1;margin:10px 0 14px;letter-spacing:-.02em}
h1 em{font-style:normal;color:var(--red2);text-shadow:0 0 28px rgba(255,42,42,.5)}
h2{font-size:clamp(24px,3.6vw,32px);margin:0 0 6px;line-height:1.2;letter-spacing:-.01em}
.lead{color:var(--mu);font-size:18px;max-width:560px;margin:0}
.sub{color:var(--mu);margin:0 0 22px;max-width:620px}
.cta{display:flex;flex-wrap:wrap;gap:12px;margin:22px 0 10px;align-items:center}
.btn{position:relative;overflow:hidden;display:inline-flex;align-items:center;justify-content:center;gap:10px;padding:13px 22px;border-radius:12px;font-weight:700;border:1px solid transparent;cursor:pointer;transition:transform .2s,box-shadow .25s,background .2s}
.btn:hover{transform:translateY(-2px)}
.btn:active{transform:translateY(0) scale(.98)}
.btn-dl{background:var(--grn);color:#03140a;box-shadow:0 0 0 rgba(0,255,102,0)}
.btn-dl:hover{box-shadow:0 8px 30px rgba(0,255,102,.45)}
.btn-red{background:var(--red);color:#fff}
.btn-red:hover{background:var(--red2);box-shadow:0 8px 30px rgba(255,42,42,.45)}
.btn-ghost{border-color:var(--line);background:var(--card)}
.btn-ghost:hover{border-color:#3a3a44;box-shadow:0 8px 26px rgba(255,255,255,.06)}
.rip{position:absolute;border-radius:50%;pointer-events:none;width:12px;height:12px;margin:-6px 0 0 -6px;background:rgba(255,255,255,.45);animation:rip .6s ease-out forwards}
@keyframes rip{to{transform:scale(18);opacity:0}}
.play{display:inline-flex;align-items:center;gap:12px;padding:9px 16px;border:1px solid var(--line);border-radius:12px;background:var(--card);opacity:.55;cursor:not-allowed;user-select:none}
.play small{display:block;font-size:10px;letter-spacing:.1em;color:var(--mu);text-transform:uppercase;line-height:1.2}
.play b{font-size:17px;line-height:1.2}
.note{font-size:13px;color:var(--mu);margin:0}
.meta{color:var(--mu);font-size:13px;margin:10px 0 0}
.gl{position:relative;overflow:hidden}
.gl::after{content:"";position:absolute;inset:0;pointer-events:none;opacity:0;transition:opacity .25s;background:radial-gradient(260px circle at var(--mx,50%) var(--my,50%),rgba(255,255,255,.09),transparent 70%)}
.gl:hover::after{opacity:1}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:20px;transition:transform .25s,border-color .25s,box-shadow .25s}
.card:hover{transform:translateY(-3px)}
.card.r:hover{border-color:rgba(255,42,42,.5);box-shadow:0 14px 40px -14px rgba(255,42,42,.5)}
.card.g:hover{border-color:rgba(0,255,102,.5);box-shadow:0 14px 40px -14px rgba(0,255,102,.45)}
.card h3{margin:0 0 6px;font-size:17px}
.card p{margin:0;color:var(--mu);font-size:15px}
.feedcard{padding:18px}
.fh{display:flex;align-items:center;gap:10px;font-size:12px;letter-spacing:.1em;text-transform:uppercase;color:var(--mu);margin-bottom:12px}
.dot{width:9px;height:9px;border-radius:50%;background:var(--grn);box-shadow:0 0 0 0 rgba(0,255,102,.6);animation:pulse 1.8s infinite}
@keyframes pulse{70%{box-shadow:0 0 0 10px rgba(0,255,102,0)}100%{box-shadow:0 0 0 0 rgba(0,255,102,0)}}
.feed{list-style:none;margin:0;padding:0;display:grid;gap:9px}
.feed li{display:flex;align-items:center;gap:10px;background:var(--card2);border:1px solid var(--line);border-radius:12px;padding:10px 12px;font-size:14px;opacity:0;animation:feedIn 10s infinite}
.feed li:nth-child(2){animation-delay:1.1s}.feed li:nth-child(3){animation-delay:2.2s}.feed li:nth-child(4){animation-delay:3.3s}
@keyframes feedIn{0%{opacity:0;transform:translateY(10px)}6%,82%{opacity:1;transform:none}92%,100%{opacity:0}}
.dt{width:8px;height:8px;border-radius:50%;flex:none}
.dt.r{background:var(--red2);box-shadow:0 0 10px var(--red2)}
.dt.g{background:var(--grn);box-shadow:0 0 10px var(--grn)}
.chips{display:flex;flex-wrap:wrap;gap:10px;margin-top:22px}
.chip{border:1px solid var(--line);background:var(--card);border-radius:999px;padding:7px 15px;font-size:14px;color:var(--mu);transition:all .2s}
.chip:hover{color:var(--tx);border-color:rgba(0,255,102,.5);box-shadow:0 0 18px rgba(0,255,102,.2)}
.seg{display:inline-flex;gap:4px;padding:4px;background:var(--card);border:1px solid var(--line);border-radius:14px;margin:0 0 18px;flex-wrap:wrap}
.seg button{position:relative;overflow:hidden;background:transparent;border:0;border-radius:10px;padding:9px 16px;font-weight:700;color:var(--mu);cursor:pointer;transition:all .2s}
.seg button:hover{color:var(--tx)}
.seg button.on{background:var(--grn);color:#03140a;box-shadow:0 0 16px rgba(0,255,102,.4)}
html.js .tabpanel{display:none}
html.js .tabpanel.on{display:block;animation:rise .4s cubic-bezier(.2,.8,.2,1)}
.ftabs{display:grid;grid-template-columns:230px 1fr;gap:16px;align-items:start}
.ftabs .seg{display:flex;flex-direction:column;margin:0;border-radius:16px}
.ftabs .seg button{text-align:start;display:flex;align-items:center;gap:10px}
.ftabs .seg button::before{content:"";width:7px;height:7px;border-radius:50%;background:var(--line);flex:none;transition:all .2s}
.ftabs .seg button.on::before{background:#03140a}
.tag{display:inline-block;font-size:11px;font-weight:800;letter-spacing:.06em;padding:3px 10px;border-radius:999px;background:rgba(0,255,102,.12);color:var(--grn);margin-bottom:10px}
.tag.red{background:rgba(255,42,42,.12);color:var(--red2)}
.fpanel h3{margin:0 0 8px;font-size:22px}
.fpanel p{color:var(--mu);margin:0 0 12px}
ul.tick{list-style:none;padding:0;margin:12px 0 0}
ul.tick li{padding-inline-start:24px;position:relative;margin:8px 0;color:#d6d6dc;font-size:15px}
ul.tick li::before{content:"";position:absolute;inset-inline-start:0;top:.6em;width:9px;height:9px;border-radius:50%;background:var(--grn);box-shadow:0 0 10px rgba(0,255,102,.7)}
.scrollx{overflow-x:auto;border-radius:16px}
.cmp{width:100%;border-collapse:separate;border-spacing:0;background:var(--card);border:1px solid var(--line);border-radius:16px;overflow:hidden;min-width:420px}
.cmp th,.cmp td{padding:12px 14px;border-bottom:1px solid var(--line);text-align:center;font-size:15px}
.cmp tr:last-child td{border-bottom:0}
.cmp th:first-child,.cmp td:first-child{text-align:start;width:58%}
.cmp td,.cmp th{transition:opacity .3s,background .3s}
.cmp .c-full{background:rgba(0,255,102,.045)}
.cmp[data-col="full"] .c-free{opacity:.35}
.cmp[data-col="free"] .c-full{opacity:.35}
.cmp tbody tr:hover td{background:rgba(255,255,255,.035)}
.yes{color:var(--grn);font-weight:800}
.no{color:#55555d}
.price{font-size:44px;font-weight:800;line-height:1.1}
.price small{font-size:16px;color:var(--mu);font-weight:600}
.badge{display:inline-block;background:var(--grn);color:#03140a;font-weight:800;font-size:11px;letter-spacing:.08em;padding:3px 10px;border-radius:999px;margin-bottom:10px}
.plan{display:grid;gap:14px;grid-template-columns:1fr 1fr;align-items:center}
details{background:var(--card);border:1px solid var(--line);border-radius:14px;margin:0 0 10px;overflow:hidden;transition:border-color .25s,box-shadow .25s}
details:hover{border-color:#34343d}
details[open]{border-color:rgba(0,255,102,.38);box-shadow:0 0 26px -10px rgba(0,255,102,.45)}
summary{cursor:pointer;list-style:none;padding:15px 18px;font-weight:700;display:flex;align-items:center;gap:12px}
summary::-webkit-details-marker{display:none}
summary::after{content:"";margin-inline-start:auto;width:9px;height:9px;border-inline-end:2px solid var(--mu);border-bottom:2px solid var(--mu);transform:rotate(45deg);transition:transform .25s;flex:none}
details[open] summary::after{transform:rotate(-135deg)}
details .dbody{padding:0 18px 16px;color:#cfcfd6}
details .dbody p{margin:0 0 8px}
.grid{display:grid;gap:14px;grid-template-columns:repeat(auto-fit,minmax(240px,1fr))}
h2.sec{margin-top:26px;font-size:20px}
.doc h1{font-size:clamp(28px,4.4vw,40px);margin:0 0 4px}
.doc .stamp{color:var(--mu);font-size:14px;margin:0 0 16px}
.xall{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:7px 14px;font-size:13px;font-weight:600;cursor:pointer;color:var(--mu);margin-bottom:14px;transition:all .2s}
.xall:hover{color:var(--tx);border-color:#3a3a44}
html:not(.js) .xall{display:none}
.copy{position:relative;z-index:1;text-align:center;color:var(--mu);font-size:14px;padding:22px 16px 28px;border-top:1px solid var(--line)}
@media(max-width:860px){
.shell{grid-template-columns:1fr}
.mbar{position:sticky;top:0;z-index:30;display:flex;align-items:center;gap:12px;padding:10px 14px;background:rgba(5,5,5,.9);-webkit-backdrop-filter:blur(12px);backdrop-filter:blur(12px);border-bottom:1px solid var(--line)}
.mbar .brand{padding:0}.mbar .brand svg{width:30px;height:30px}
.mbar .lang{margin-inline-start:auto}
.burger{width:40px;height:40px;border-radius:10px;border:1px solid var(--line);background:var(--card);display:grid;place-items:center;cursor:pointer}
.burger span,.burger span::before,.burger span::after{display:block;width:18px;height:2px;background:var(--tx);border-radius:2px;content:"";position:relative;transition:transform .25s,background .25s}
.burger span::before{position:absolute;top:-6px}.burger span::after{position:absolute;top:6px}
.side{position:fixed;z-index:40;inset-block:0;inset-inline-start:0;width:min(82vw,290px);transform:translateX(-105%);transition:transform .3s cubic-bezier(.2,.8,.2,1);background:rgba(8,8,10,.97)}
html[dir="rtl"] .side{transform:translateX(105%)}
.side.open{transform:none;box-shadow:0 0 60px rgba(0,0,0,.8)}
.side .brand,.side .lang{display:none}
.scrim{display:block;position:fixed;inset:0;z-index:35;background:rgba(0,0,0,.6);opacity:0;pointer-events:none;transition:opacity .3s}
.scrim.open{opacity:1;pointer-events:auto}
.hero{grid-template-columns:1fr}
.ftabs{grid-template-columns:1fr}
.ftabs .seg{flex-direction:row;overflow-x:auto;flex-wrap:nowrap;border-radius:14px}
.ftabs .seg button{white-space:nowrap}
.plan{grid-template-columns:1fr}
}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}.feed li{opacity:1}}
`;

const HEAD_JS = `(function(){var l="";try{l=localStorage.getItem("tt_lang")||""}catch(e){}if(l!=="ar"&&l!=="en")l=(navigator.language||"").slice(0,2)==="ar"?"ar":"en";var h=document.documentElement;h.setAttribute("data-l",l);h.lang=l;h.dir=l==="ar"?"rtl":"ltr";h.className+=" js"})();`;

const BODY_JS = `(function(){
var d=document,h=d.documentElement;
function $$(s,r){return Array.prototype.slice.call((r||d).querySelectorAll(s))}
/* language */
function mark(l){$$(".lang button").forEach(function(b){b.setAttribute("aria-pressed",b.getAttribute("data-set")===l?"true":"false")})}
mark(h.getAttribute("data-l"));
$$(".lang button").forEach(function(b){b.addEventListener("click",function(){var l=b.getAttribute("data-set");h.setAttribute("data-l",l);h.lang=l;h.dir=l==="ar"?"rtl":"ltr";mark(l);try{localStorage.setItem("tt_lang",l)}catch(e){}})});
/* drawer */
var side=d.getElementById("side"),scrim=d.getElementById("scrim"),burger=d.getElementById("burger");
function drawer(o){side.classList.toggle("open",o);scrim.classList.toggle("open",o);burger.setAttribute("aria-expanded",o?"true":"false")}
burger.addEventListener("click",function(){drawer(!side.classList.contains("open"))});
scrim.addEventListener("click",function(){drawer(false)});
d.addEventListener("keydown",function(e){if(e.key==="Escape")drawer(false)});
/* panels (landing only) */
var panels=$$(".panel[id]");
function show(id,push){
  if(!panels.some(function(p){return p.id===id}))id="home";
  panels.forEach(function(p){p.classList.toggle("on",p.id===id)});
  $$(".menu a[data-go]").forEach(function(a){a.classList.toggle("on",a.getAttribute("data-go")===id)});
  if(push&&location.hash!=="#"+id)location.hash=id;
  window.scrollTo(0,0);drawer(false);
}
if(panels.length){
  $$("[data-go]").forEach(function(a){a.addEventListener("click",function(e){e.preventDefault();show(a.getAttribute("data-go"),true)})});
  window.addEventListener("hashchange",function(){show(location.hash.slice(1),false)});
  show(location.hash.slice(1),false);
}
/* tabs: a [data-tabs] box holds a .seg of buttons[data-t] and a .tabbody of .tabpanel children */
$$("[data-tabs]").forEach(function(box){
  var seg=box.querySelector(".seg"),body=box.querySelector(".tabbody");
  $$("button[data-t]",seg).forEach(function(b){b.addEventListener("click",function(){
    $$("button[data-t]",seg).forEach(function(x){var on=x===b;x.classList.toggle("on",on);x.setAttribute("aria-selected",on?"true":"false")});
    Array.prototype.forEach.call(body.children,function(p){p.classList.toggle("on",p.id===b.getAttribute("data-t"))});
  })});
});
/* compare column highlight */
$$("button[data-col]").forEach(function(b){b.addEventListener("click",function(){
  var t=d.getElementById("cmp");if(t)t.setAttribute("data-col",b.getAttribute("data-col"));
  $$("button[data-col]").forEach(function(x){x.classList.toggle("on",x===b)});
})});
/* legal: expand / collapse all */
$$(".xall").forEach(function(b){b.addEventListener("click",function(){
  var open=b.getAttribute("data-o")!=="1";b.setAttribute("data-o",open?"1":"0");
  $$("details",d.querySelector(".doc")).forEach(function(x){x.open=open});
})});
/* light that follows the pointer + tap ripple */
var spot=d.querySelector(".spot"),raf=0,px=0,py=0;
d.addEventListener("pointermove",function(e){
  px=e.clientX;py=e.clientY;
  if(!raf)raf=requestAnimationFrame(function(){raf=0;spot.style.setProperty("--px",px+"px");spot.style.setProperty("--py",py+"px")});
  var g=e.target.closest?e.target.closest(".gl"):null;
  if(g){var r=g.getBoundingClientRect();g.style.setProperty("--mx",(e.clientX-r.left)+"px");g.style.setProperty("--my",(e.clientY-r.top)+"px")}
});
d.addEventListener("pointerdown",function(e){
  var b=e.target.closest?e.target.closest(".btn,.menu a,.seg button"):null;if(!b)return;
  var r=b.getBoundingClientRect(),s=d.createElement("span");s.className="rip";
  s.style.left=(e.clientX-r.left)+"px";s.style.top=(e.clientY-r.top)+"px";
  b.style.position=b.style.position||"";b.appendChild(s);setTimeout(function(){if(s.parentNode)s.parentNode.removeChild(s)},650);
});
/* latest version label */
var v=d.getElementById("ver");
if(v){fetch("/version").then(function(r){return r.json()}).then(function(j){if(j&&j.latest_version_name){v.textContent="v"+j.latest_version_name}}).catch(function(){})}
})();`;

function sidebar(active) {
  const item = (id, icon, en, ar, href) =>
    `<a href="${href}" ${href.startsWith("/#") ? `data-go="${id}"` : ""} class="${active === id ? "on" : ""}">${ico(icon)}<span>${sp(en, ar)}</span></a>`;
  return `<aside class="side" id="side">
<a class="brand" href="/">${SHIELD}<span>THIEVES TRAP</span></a>
<nav class="menu">
${item("home", "home", "Home", "الرئيسية", "/#home")}
${item("features", "features", "Features", "المزايا", "/#features")}
${item("plans", "plans", "Plans", "الباقات", "/#plans")}
${item("install", "install", "Install", "التثبيت", "/#install")}
${item("support", "support", "Support", "الدعم", "/#support")}
<hr>
${item("terms", "terms", "Terms of Use", "شروط الاستخدام", "/terms")}
${item("privacy", "privacy", "Privacy Policy", "سياسة الخصوصية", "/privacy")}
</nav>
<div class="sfoot"><div class="lang"><button type="button" data-set="en" aria-pressed="true">EN</button><button type="button" data-set="ar" aria-pressed="false">عربي</button></div></div>
</aside>`;
}

function shell(title, desc, active, content, n) {
  return `<!doctype html>
<html lang="en" dir="ltr" data-l="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#050505">
<title>${title}</title>
<meta name="description" content="${desc}">
<meta property="og:title" content="${title}">
<meta property="og:description" content="${desc}">
<meta property="og:type" content="website">
<link rel="icon" href="${FAVICON}">
<script nonce="${n}">${HEAD_JS}</script>
<style nonce="${n}">${CSS}</style>
</head><body>
<div class="spot" aria-hidden="true"></div>
<header class="mbar"><button class="burger" id="burger" type="button" aria-label="Menu" aria-expanded="false"><span></span></button><a class="brand" href="/">${SHIELD}<span>THIEVES TRAP</span></a><div class="lang"><button type="button" data-set="en" aria-pressed="true">EN</button><button type="button" data-set="ar" aria-pressed="false">عربي</button></div></header>
<div class="shell">
${sidebar(active)}
<div class="scrim" id="scrim"></div>
<main class="main">
${content}
</main>
</div>
<footer class="copy">© 2026 iLine Tech by Ferak Aladdin</footer>
<script nonce="${n}">${BODY_JS}</script>
</body></html>`;
}

// ---------------------------------------------------------------- landing panels

const FEATS = [
  { id: "f-selfie", red: true, tag: ["Full Protection", "الحماية الكاملة"], t: ["Intruder selfie", "صورة المتسلل"],
    d: ["Photographs anyone who tries to unlock your phone with the wrong code.", "يلتقط صورة لكل من يحاول فتح هاتفك برمز خاطئ."],
    b: [["Triggered by a wrong PIN, pattern or password", "يُفعَّل عند إدخال رمز PIN أو نمط أو كلمة مرور خاطئة"], ["The photo is saved on your phone", "تُحفظ الصورة على هاتفك"], ["Sent to your private Telegram bot", "تُرسل إلى بوت تيليجرام الخاص بك"]] },
  { id: "f-remote", tag: ["WHERE is free", "أمر WHERE مجاني"], t: ["Remote SMS control", "التحكم عن بُعد بالرسائل"],
    d: ["Find, ring or lock your phone from any other phone by sending a short SMS.", "حدّد مكان هاتفك أو شغّل الإنذار أو اقفله من أي هاتف آخر برسالة قصيرة."],
    b: [["WHERE replies with a map link", "الأمر WHERE يردّ برابط خريطة"], ["ALARM and STOP ALARM, LOCK, INFO, BATTERY, SIM", "ALARM وSTOP ALARM وLOCK وINFO وBATTERY وSIM"], ["Works from any number with your PIN: COMMAND + PIN", "يعمل من أي رقم مع رمزك: الأمر + PIN"]] },
  { id: "f-sim", red: true, t: ["SIM-swap trap", "فخ تبديل الشريحة"],
    d: ["Catches the moment a thief removes or replaces your SIM card.", "يرصد لحظة إزالة اللص لشريحتك أو استبدالها."],
    b: [["Detects SIM removal or change", "يرصد إزالة الشريحة أو تغييرها"], ["Waits for a signal, then sends one SMS", "ينتظر وجود إشارة ثم يرسل رسالة واحدة"], ["Includes the new carrier, IMEI and location", "تتضمن المشغّل الجديد وIMEI والموقع"]] },
  { id: "f-watch", tag: ["Full Protection", "الحماية الكاملة"], t: ["Watch Tether", "ربط الساعة الذكية"],
    d: ["Your smartwatch becomes a leash: walk away from the phone and it reacts.", "تتحول ساعتك الذكية إلى رباط: ابتعد عن الهاتف فيتفاعل."],
    b: [["Pair a Bluetooth smartwatch", "اربط ساعة ذكية عبر البلوتوث"], ["If it disconnects the phone locks and a 5-minute countdown starts", "عند انقطاعها يُقفل الهاتف ويبدأ عدّ تنازلي لمدة 5 دقائق"], ["Reconnecting cancels it; otherwise an SMS with your location is sent", "إعادة الاتصال تُلغيه، وإلا تُرسل رسالة بموقعك"]] },
  { id: "f-timer", red: true, t: ["Survival Timer", "مؤقّت الأمان"],
    d: ["A dead-man's switch for risky moments.", "مفتاح طوارئ للمواقف الخطرة."],
    b: [["Set a check-in time", "حدّد وقتاً لتأكيد سلامتك"], ["Tap “I'm Safe” to cancel, no PIN needed", "اضغط «أنا بخير» للإلغاء دون الحاجة إلى PIN"], ["If you do not, an emergency SMS goes out", "وإلا تُرسل رسالة طوارئ"]] },
  { id: "f-siren", tag: ["Full Protection", "الحماية الكاملة"], t: ["Loud siren", "صفارة إنذار عالية"],
    d: ["Make a lost or stolen phone impossible to ignore.", "اجعل الهاتف المفقود أو المسروق يلفت الانتباه."],
    b: [["Maximum-volume siren", "صفارة بأعلى مستوى للصوت"], ["Start and stop it by SMS", "شغّلها وأوقفها برسالة"], ["Can also be triggered from the watch alert", "يمكن تفعيلها أيضاً من تنبيه الساعة"]] },
  { id: "f-priv", t: ["Private by design", "خصوصية أولاً"],
    d: ["Your data goes where you send it, not to us.", "بياناتك تذهب إلى حيث ترسلها أنت، لا إلينا."],
    b: [["No account needed", "لا حاجة لحساب"], ["Photos and location reach only your contacts and your bot", "الصور والموقع تصل فقط إلى جهات اتصالك وبوتك"], ["The license server sees only your key, a device label and dates", "خادم التراخيص لا يرى إلا مفتاحك ووسم الجهاز والتواريخ"]] },
  { id: "f-lang", t: ["English, French & Arabic", "الإنجليزية والفرنسية والعربية"],
    d: ["The whole app speaks your language.", "التطبيق كله بلغتك."],
    b: [["Three full languages", "ثلاث لغات كاملة"], ["Right-to-left layout for Arabic", "واجهة من اليمين إلى اليسار للعربية"], ["Switch the language in the app at any time", "بدّل اللغة داخل التطبيق في أي وقت"]] },
];

function featuresPanel() {
  const buttons = FEATS.map((f, i) => `<button type="button" role="tab" data-t="${f.id}" class="${i === 0 ? "on" : ""}" aria-selected="${i === 0}">${sp(f.t[0], f.t[1])}</button>`).join("");
  const panels = FEATS.map((f, i) => `<div class="tabpanel fpanel card gl ${f.red ? "r" : "g"} ${i === 0 ? "on" : ""}" id="${f.id}">
${f.tag ? `<span class="tag ${f.red ? "red" : ""}">${sp(f.tag[0], f.tag[1])}</span>` : ""}
<h3>${sp(f.t[0], f.t[1])}</h3>${bi("p", f.d[0], f.d[1])}
<ul class="tick en">${f.b.map((x) => `<li>${x[0]}</li>`).join("")}</ul><ul class="tick ar" lang="ar" dir="rtl">${f.b.map((x) => `<li>${x[1]}</li>`).join("")}</ul>
</div>`).join("");
  return `<section class="panel" id="features">
${bi("h2", "Built to catch thieves", "مصمم لإيقاع اللصوص", "st")}
${bi("p", "Pick a feature to see how it works.", "اختر ميزة لترى كيف تعمل.", "sub st")}
<div class="ftabs st" data-tabs><div class="seg" role="tablist">${buttons}</div><div class="tabbody">${panels}</div></div>
</section>`;
}

const ROWS = [
  [["Basic protection and activity log", "حماية أساسية وسجل للأحداث"], 1, 1],
  [["Remote location by SMS (WHERE)", "تحديد الموقع عن بُعد برسالة (WHERE)"], 1, 1],
  [["All remote commands: ALARM, LOCK, INFO, BATTERY…", "كل أوامر التحكم: ALARM وLOCK وINFO وBATTERY…"], 0, 1],
  [["Intruder selfies and Telegram alerts", "صور المتسلل وتنبيهات تيليجرام"], 0, 1],
  [["Full Theft Mode", "وضع السرقة الكامل"], 0, 1],
  [["Watch Tether", "ربط الساعة الذكية"], 0, 1],
  [["Second emergency contact", "جهة اتصال طوارئ ثانية"], 0, 1],
];

const PLANS = [
  { id: "p-m", name: ["Monthly", "شهري"], price: "5.99", days: ["30 days from activation", "30 يوماً من التفعيل"], per: "5.99" },
  { id: "p-q", name: ["3 Months", "3 أشهر"], price: "10.99", days: ["90 days from activation", "90 يوماً من التفعيل"], per: "3.66" },
  { id: "p-a", name: ["Annual", "سنوي"], price: "24.99", days: ["365 days from activation", "365 يوماً من التفعيل"], per: "2.08", best: true },
];

function plansPanel() {
  const rows = ROWS.map((r) => `<tr><td>${sp(r[0][0], r[0][1])}</td><td class="c-free">${r[1] ? '<span class="yes">✓</span>' : '<span class="no">—</span>'}</td><td class="c-full">${r[2] ? '<span class="yes">✓</span>' : '<span class="no">—</span>'}</td></tr>`).join("");
  const chips = PLANS.map((p) => `<button type="button" role="tab" data-t="${p.id}" class="${p.best ? "on" : ""}" aria-selected="${!!p.best}">${sp(p.name[0], p.name[1])}</button>`).join("");
  const cards = PLANS.map((p) => `<div class="tabpanel card gl g ${p.best ? "on" : ""}" id="${p.id}"><div class="plan"><div>
${p.best ? `<span class="badge">${sp("BEST VALUE", "الأفضل قيمة")}</span>` : ""}
<h3>${sp(p.name[0], p.name[1])}</h3><div class="price">${p.price} <small>€</small></div>${bi("p", p.days[0], p.days[1])}
<p class="note">${sp("≈ " + p.per + " € per month", "≈ " + p.per + " € شهرياً")}</p></div>
<div><a class="btn btn-red" href="${MESSENGER_URL}" target="_blank" rel="noopener noreferrer">${sp("Get a key on Messenger", "احصل على مفتاح عبر ماسنجر")}</a></div></div></div>`).join("");
  return `<section class="panel" id="plans">
${bi("h2", "Free to start. Full Protection when you need it.", "ابدأ مجاناً. والحماية الكاملة عند الحاجة.", "st")}
${bi("p", "Install the free version first. Unlock Full Protection with a license key.", "ثبّت النسخة المجانية أولاً. وافتح الحماية الكاملة بمفتاح ترخيص.", "sub st")}
<div class="st" data-tabs>
<div class="seg" role="tablist"><button type="button" role="tab" data-t="v-cmp" class="on" aria-selected="true">${sp("Compare", "مقارنة")}</button><button type="button" role="tab" data-t="v-price" aria-selected="false">${sp("Pricing", "الأسعار")}</button></div>
<div class="tabbody">
<div class="tabpanel on" id="v-cmp">
<div class="seg"><button type="button" data-col="free">${sp("Free", "مجاني")}</button><button type="button" data-col="full" class="on">${sp("Full Protection", "الحماية الكاملة")}</button></div>
<div class="scrollx"><table class="cmp" id="cmp" data-col="full"><thead><tr><th></th><th class="c-free">${sp("Free", "مجاني")}</th><th class="c-full">${sp("Full Protection", "الحماية الكاملة")}</th></tr></thead><tbody>${rows}</tbody></table></div>
</div>
<div class="tabpanel" id="v-price">
<div data-tabs><div class="seg" role="tablist">${chips}</div><div class="tabbody">${cards}</div></div>
${bi("p", "One key activates one phone. Keys do not renew automatically. Prices in euros.", "المفتاح الواحد يفعّل هاتفاً واحداً. المفاتيح لا تتجدد تلقائياً. الأسعار باليورو.", "note")}
</div>
</div></div>
</section>`;
}

const det = (cls, name, open, title, body) =>
  `<details class="${cls}" ${name ? `name="${name}"` : ""} ${open ? "open" : ""}><summary>${title}</summary><div class="dbody">${body}</div></details>`;
// one accordion item in both languages: [enTitle, arTitle, enBody, arBody]
const acc = (group, i, x) =>
  det("en", group + "-en", i === 0, x[0], `<p>${x[2]}</p>`) + det("ar\" lang=\"ar\" dir=\"rtl", group + "-ar", i === 0, x[1], `<p>${x[3]}</p>`);

function installPanel() {
  const steps = [
    ["1. Download the APK", "1. نزّل ملف APK", "Tap <b>Download APK</b> and open the file when it finishes. It is a normal Android installer.", "اضغط <b>تحميل ملف APK</b> وافتح الملف عند اكتمال التنزيل. إنه مثبّت أندرويد عادي."],
    ["2. Allow the installation", "2. اسمح بالتثبيت", "If Android asks, allow “Install unknown apps” for your browser or file manager, then tap Install.", "إذا طلب أندرويد ذلك، اسمح بخيار «تثبيت التطبيقات غير المعروفة» لمتصفحك أو مدير الملفات، ثم اضغط تثبيت."],
    ["3. Set up and arm", "3. الإعداد والتفعيل", "Open Thieves Trap, set your PIN and emergency contact, grant the permissions and tap ARM. Updates arrive inside the app.", "افتح Thieves Trap، وحدّد رمز PIN وجهة اتصال الطوارئ، وامنح الأذونات ثم اضغط ARM. تصلك التحديثات داخل التطبيق."],
  ];
  return `<section class="panel" id="install">
${bi("h2", "Install in three steps", "التثبيت في ثلاث خطوات", "st")}
${bi("p", "Thieves Trap is installed from an APK file until it is available on Google Play.", "يُثبَّت Thieves Trap من ملف APK إلى أن يتوفر على متجر Google Play.", "sub st")}
<div class="st">${steps.map((s, i) => acc("inst", i, s)).join("")}</div>
<div class="cta st"><a class="btn btn-dl" href="/download" rel="nofollow">${sp("Download APK", "تحميل ملف APK")}</a></div>
</section>`;
}

function supportPanel() {
  const faq = [
    ["Is Thieves Trap free?", "هل Thieves Trap مجاني؟", "The free plan includes basic protection and remote location by SMS. Full Protection needs a license key.", "تتضمن الباقة المجانية حماية أساسية وتحديد الموقع عن بُعد برسالة. أما الحماية الكاملة فتحتاج إلى مفتاح ترخيص."],
    ["Can I use one key on two phones?", "هل أستخدم مفتاحاً واحداً على هاتفين؟", "No. A key is locked to the first phone that activates it. If you change phones, contact support and we will release the key for your new phone.", "لا. يرتبط المفتاح بأول هاتف يفعّله. إذا غيّرت هاتفك فتواصل مع الدعم وسنحرّر المفتاح لهاتفك الجديد."],
    ["Does it need internet?", "هل يحتاج إلى إنترنت؟", "Alerts and remote commands work by SMS. The license is verified online from time to time and stays valid for up to 72 hours between checks.", "تعمل التنبيهات وأوامر التحكم عبر الرسائل القصيرة. يُتحقق من الترخيص عبر الإنترنت من وقت لآخر ويبقى صالحاً حتى 72 ساعة بين الفحوصات."],
    ["Where do the photos and location go?", "أين تذهب الصور والموقع؟", "Only to your emergency contacts and your own Telegram bot. We do not host them.", "فقط إلى جهات اتصال الطوارئ وبوت تيليجرام الخاص بك. نحن لا نستضيفها."],
    ["How do I renew?", "كيف أجدد الاشتراك؟", "Message us on Messenger or Facebook. We extend your existing key, so you keep using the same one.", "راسلنا على ماسنجر أو فيسبوك. نمدّد مفتاحك الحالي لتواصل استخدام المفتاح نفسه."],
  ];
  return `<section class="panel" id="support">
${bi("h2", "Support and license keys", "الدعم ومفاتيح الترخيص", "st")}
${bi("p", "We answer on Facebook and Messenger.", "نرد عبر فيسبوك وماسنجر.", "sub st")}
<div class="grid st">
<div class="card gl g"><h3>${sp("Get or renew a key", "احصل على مفتاح أو جدّده")}</h3><p>${sp("Choose a plan and message us on Messenger.", "اختر باقة وراسلنا على ماسنجر.")}</p><div class="cta"><a class="btn btn-red" href="${MESSENGER_URL}" target="_blank" rel="noopener noreferrer">${sp("Open Messenger", "افتح ماسنجر")}</a></div></div>
<div class="card gl r"><h3>${sp("Need help?", "تحتاج مساعدة؟")}</h3><p>${sp("Questions, problems or changing phone.", "أسئلة أو مشاكل أو تغيير الهاتف.")}</p><div class="cta"><a class="btn btn-ghost" href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">${sp("Facebook page", "صفحة فيسبوك")}</a></div></div>
</div>
${bi("h2", "Frequently asked questions", "الأسئلة الشائعة", "sec st")}
<div class="st">${faq.map((x, i) => acc("faq", i, x)).join("")}</div>
</section>`;
}

function landingContent() {
  return `<section class="panel on" id="home">
<div class="hero">
<div>
<div class="eyebrow st">${sp("Android anti-theft &amp; phone security", "الحماية من السرقة وأمان الهاتف لأندرويد")}</div>
<h1 class="st">${sp("Your phone is <em>safe</em> with us.", "هاتفك <em>في أمان</em> معنا.")}</h1>
${bi("p", "If someone steals your phone or tries to unlock it, Thieves Trap alerts you by SMS with a GPS location, photographs the intruder, and lets you find, ring or lock the phone from any other phone.",
"إذا سُرق هاتفك أو حاول أحدهم فتحه، يرسل لك Thieves Trap تنبيهاً برسالة SMS مع موقعك عبر GPS، ويلتقط صورة للمتسلل، ويتيح لك تحديد مكان الهاتف أو تشغيل صفارة الإنذار أو قفله من أي هاتف آخر.", "lead st")}
<div class="cta st">
<a class="btn btn-dl" href="/download" rel="nofollow">${sp("Download APK", "تحميل ملف APK")}</a>
<a class="btn btn-ghost" href="/#plans" data-go="plans">${sp("Compare plans", "قارن الباقات")}</a>
</div>
<div class="cta st">
<div class="play" aria-disabled="true"><svg width="26" height="26" viewBox="0 0 24 24" aria-hidden="true"><path fill="#00ff66" d="M3.6 2.2 14 12 3.6 21.8c-.4-.2-.6-.6-.6-1.1V3.3c0-.5.2-.9.6-1.1z"/><path fill="#cc0000" d="M14 12 17.4 8.8l3.5 2c.9.5.9 1.9 0 2.4l-3.5 2z"/><path fill="#a0a0a8" d="M3.6 2.2c.3-.2.8-.2 1.2.1L17.4 8.8 14 12z"/><path fill="#a0a0a8" opacity=".7" d="M14 12l3.4 3.2L4.8 21.7c-.4.3-.9.3-1.2.1z"/></svg><span><small>${sp("Get it on", "حمّله من")}</small><b>Google Play</b></span></div>
<p class="note">${sp("Not available on the store yet, coming soon", "غير متوفر حالياً في المتجر، قريباً")}</p>
</div>
<p class="meta st">${sp("Free plan included · Updates inside the app · Latest version:", "باقة مجانية متاحة · التحديثات داخل التطبيق · أحدث إصدار:")} <span id="ver">…</span></p>
</div>
<div class="card feedcard gl r st">
<div class="fh"><span class="dot"></span>${sp("What you receive", "ما يصلك")}</div>
<ul class="feed">
<li><span class="dt r"></span>${sp("Wrong screen-lock attempt #2", "محاولة فتح خاطئة للشاشة رقم 2")}</li>
<li><span class="dt r"></span>${sp("Intruder selfie captured", "تم التقاط صورة للمتسلل")}</li>
<li><span class="dt g"></span>${sp("SMS sent with GPS location", "تم إرسال رسالة بموقع GPS")}</li>
<li><span class="dt r"></span>${sp("SIM card changed: new carrier reported", "تغيّرت الشريحة: تم إرسال المشغّل الجديد")}</li>
</ul></div>
</div>
<div class="chips st"><span class="chip">${sp("No account needed", "بدون حساب")}</span><span class="chip">${sp("Works by SMS", "يعمل بالرسائل القصيرة")}</span><span class="chip">${sp("English · Français · العربية", "الإنجليزية · الفرنسية · العربية")}</span></div>
</section>
${featuresPanel()}
${plansPanel()}
${installPanel()}
${supportPanel()}`;
}

// ---------------------------------------------------------------- legal pages (accordions)

const sec = (i, hEn, hAr, pEn, pAr) => det("en", "", i === 0, hEn, `<p>${pEn}</p>`) + det("ar\" lang=\"ar\" dir=\"rtl", "", i === 0, hAr, `<p>${pAr}</p>`);

function docContent(titleEn, titleAr, sections) {
  return `<section class="panel on doc">
${bi("h1", titleEn, titleAr)}
<p class="stamp en">Thieves Trap by iLine Tech · Last updated ${UPDATED.en}</p><p class="stamp ar" lang="ar" dir="rtl">Thieves Trap من iLine Tech · آخر تحديث ${UPDATED.ar}</p>
<button type="button" class="xall">${sp("Expand / collapse all", "فتح / إغلاق الكل")}</button>
${sections.map((s, i) => sec(i, s[0], s[1], s[2], s[3])).join("")}
</section>`;
}

const contactEn = `Questions: <a href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">our Facebook page</a>.`;
const contactAr = `للاستفسار: <a href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">صفحتنا على فيسبوك</a>.`;

function termsContent() {
  return docContent("Terms of Use", "شروط الاستخدام", [
    ["1. Acceptance", "1. القبول", "By installing or using Thieves Trap you agree to these Terms of Use and to our Privacy Policy. If you do not agree, please do not use the app.",
      "بتثبيت Thieves Trap أو استخدامه فإنك توافق على شروط الاستخدام هذه وعلى سياسة الخصوصية. إذا كنت لا توافق فلا تستخدم التطبيق."],
    ["2. What the app does", "2. ما يقدمه التطبيق", "Thieves Trap is an Android anti-theft and phone-safety app. It can send SMS alerts with a GPS location, take photos of intruders, and respond to remote SMS commands that you authorise.",
      "Thieves Trap تطبيق أندرويد للحماية من السرقة وأمان الهاتف. يمكنه إرسال تنبيهات SMS مع موقع GPS، والتقاط صور للمتسللين، والاستجابة لأوامر SMS عن بُعد التي تأذن بها."],
    ["3. Lawful and consensual use", "3. الاستخدام المشروع وبموافقة الأطراف", "Use the app only on devices you own or are authorised to protect. You must not use it to monitor, track or photograph another person without their knowledge and consent. You are responsible for complying with the laws that apply to you.",
      "استخدم التطبيق فقط على أجهزة تملكها أو مخوّل بحمايتها. يُمنع استخدامه لمراقبة شخص آخر أو تتبعه أو تصويره دون علمه وموافقته. أنت مسؤول عن الالتزام بالقوانين السارية عليك."],
    ["4. Permissions", "4. الأذونات", "To work, the app needs Device Administrator, Camera, Location, SMS and Bluetooth access. You can withdraw a permission at any time in Android settings, but the related protection features will then stop working.",
      "لكي يعمل التطبيق يحتاج إلى صلاحيات مشرف الجهاز والكاميرا والموقع والرسائل والبلوتوث. يمكنك سحب أي إذن في أي وقت من إعدادات أندرويد، لكن ميزات الحماية المرتبطة به ستتوقف."],
    ["5. Licenses and plans", "5. التراخيص والباقات", "Full Protection is unlocked with a license key (Monthly 30 days, 3 Months 90 days, Annual 365 days, or a trial). A key works on one device at a time, is checked online and is tied to that device. Its period starts at the first activation, and keys do not renew automatically; support can extend an existing key. A key can be revoked if it is shared, resold or obtained fraudulently.",
      "تُفتح الحماية الكاملة بمفتاح ترخيص (شهري 30 يوماً، 3 أشهر 90 يوماً، سنوي 365 يوماً، أو نسخة تجريبية). يعمل المفتاح على جهاز واحد في كل مرة، ويُتحقق منه عبر الإنترنت ويرتبط بذلك الجهاز. تبدأ مدته عند أول تفعيل، ولا تتجدد المفاتيح تلقائياً، ويمكن للدعم تمديد مفتاح قائم. يجوز إلغاء المفتاح إذا تمت مشاركته أو إعادة بيعه أو الحصول عليه بطريقة احتيالية."],
    ["6. Costs and network", "6. التكاليف والشبكة", "Alerts use SMS and, where enabled, the internet. Your carrier may charge for messages. Alerts depend on signal, GPS, battery and the phone being switched on.",
      "تعتمد التنبيهات على الرسائل القصيرة وعلى الإنترنت عند تفعيله. قد يفرض مشغّل الشبكة رسوماً على الرسائل. وتعتمد التنبيهات على الإشارة وGPS والبطارية وعلى كون الهاتف قيد التشغيل."],
    ["7. No guarantee", "7. عدم وجود ضمان", "Thieves Trap helps you locate and respond, but we cannot guarantee that a phone will be recovered or that every alert will be delivered. The app is provided “as is”.",
      "يساعدك Thieves Trap على تحديد المكان والاستجابة، لكننا لا نضمن استرجاع الهاتف ولا وصول كل تنبيه. يُقدَّم التطبيق «كما هو»."],
    ["8. Limitation of liability", "8. حدود المسؤولية", "To the extent permitted by law, iLine Tech is not liable for indirect or consequential loss, or for loss caused by carrier, network or device limitations.",
      "في الحدود التي يسمح بها القانون، لا تتحمل iLine Tech مسؤولية الخسائر غير المباشرة أو التبعية، ولا الخسائر الناتجة عن قيود المشغّل أو الشبكة أو الجهاز."],
    ["9. Updates", "9. التحديثات", "The app can check for and download updates from our update server. Some updates may be mandatory so that security fixes and license checks keep working. Your protection keeps running in the background while you update.",
      "يمكن للتطبيق البحث عن التحديثات وتنزيلها من خادم التحديثات لدينا. قد تكون بعض التحديثات إلزامية ليستمر عمل إصلاحات الأمان وفحص التراخيص. تستمر حمايتك في العمل في الخلفية أثناء التحديث."],
    ["10. Changes and contact", "10. التعديلات والتواصل", "We may update these terms; the date above shows the latest revision. " + contactEn,
      "قد نحدّث هذه الشروط، ويبيّن التاريخ أعلاه آخر مراجعة. " + contactAr],
  ]);
}

function privacyContent() {
  return docContent("Privacy Policy", "سياسة الخصوصية", [
    ["1. In short", "1. باختصار", "Your photos, location and settings stay on your phone and are sent only to the emergency contacts and the private Telegram bot you choose. We do not host or store them on our servers.",
      "تبقى صورك وموقعك وإعداداتك على هاتفك، ولا تُرسل إلا إلى جهات اتصال الطوارئ وبوت تيليجرام الخاص الذي تختاره. نحن لا نستضيفها ولا نخزنها على خوادمنا."],
    ["2. Data on your device", "2. البيانات على جهازك", "Your PIN, emergency contacts, settings, activity log and intruder photos are stored locally in the app's private storage. Uninstalling the app removes them.",
      "يُخزَّن رمز PIN وجهات اتصال الطوارئ والإعدادات وسجل الأحداث وصور المتسللين محلياً في مساحة التطبيق الخاصة. وحذف التطبيق يزيلها."],
    ["3. Data sent from your phone", "3. البيانات المرسلة من هاتفك", "SMS alerts (location, battery and device details such as SIM and IMEI information) go to the contacts you set, through your carrier. If you connect a Telegram bot, messages and intruder photos go to that bot through Telegram.",
      "تُرسل تنبيهات SMS (الموقع والبطارية وتفاصيل الجهاز مثل معلومات الشريحة وIMEI) إلى جهات الاتصال التي حددتها عبر مشغّل الشبكة. وإذا ربطت بوت تيليجرام فستُرسل الرسائل وصور المتسللين إلى ذلك البوت عبر تيليجرام."],
    ["4. License server", "4. خادم التراخيص", "When you activate a key, and about every 15 minutes while online, the app contacts our license server (hosted on Cloudflare). It sends the license key, an Android device identifier used as a label, a public key generated on your phone, and timestamps. The server keeps the key's plan, dates, device label and status. It never receives your location, photos, contacts or messages.",
      "عند تفعيل مفتاح، وكل 15 دقيقة تقريباً أثناء الاتصال بالإنترنت، يتصل التطبيق بخادم التراخيص لدينا (المستضاف على Cloudflare). يرسل مفتاح الترخيص ومعرّف جهاز أندرويد يُستخدم كوسم ومفتاحاً عاماً يُنشأ على هاتفك وبصمات زمنية. يحتفظ الخادم بباقة المفتاح وتواريخه ووسم الجهاز وحالته. ولا يستلم أبداً موقعك أو صورك أو جهات اتصالك أو رسائلك."],
    ["5. Updates and this website", "5. التحديثات وهذا الموقع", "Checking for updates reads a small file from our update server, and the app file is downloaded from the same service. That hosting service and Cloudflare may log technical data such as your IP address under their own policies. This website has no cookies or analytics; it only remembers your language choice in your browser.",
      "يقرأ فحص التحديثات ملفاً صغيراً من خادم التحديثات لدينا، ويُنزَّل ملف التطبيق من الخدمة نفسها. قد تسجّل خدمة الاستضافة هذه وCloudflare بيانات تقنية مثل عنوان IP وفق سياساتها. لا يستخدم هذا الموقع ملفات تعريف الارتباط ولا أدوات تحليل، ويحفظ فقط اختيارك للغة في متصفحك."],
    ["6. Permissions", "6. الأذونات", "Each permission is used only for its protection feature: Device Administrator (detect failed unlock attempts and lock the screen), Camera (intruder selfies), Location (GPS position in alerts), SMS (send alerts and receive your remote commands), Bluetooth (Watch Tether).",
      "يُستخدم كل إذن لميزة الحماية الخاصة به فقط: مشرف الجهاز (رصد محاولات الفتح الفاشلة وقفل الشاشة)، الكاميرا (صور المتسللين)، الموقع (موقع GPS في التنبيهات)، الرسائل (إرسال التنبيهات واستقبال أوامرك عن بُعد)، البلوتوث (ربط الساعة الذكية)."],
    ["7. Retention and deletion", "7. الاحتفاظ والحذف", "License records are kept while the key exists. To have a record deleted, contact support. Data on your phone is yours to delete at any time from the app or by uninstalling it.",
      "تُحفظ سجلات التراخيص ما دام المفتاح قائماً. لطلب حذف سجل تواصل مع الدعم. أما البيانات على هاتفك فهي لك، يمكنك حذفها في أي وقت من التطبيق أو بحذفه."],
    ["8. Changes and contact", "8. التعديلات والتواصل", "We may update this policy; the date above shows the latest revision. " + contactEn,
      "قد نحدّث هذه السياسة، ويبيّن التاريخ أعلاه آخر مراجعة. " + contactAr],
  ]);
}

// ---------------------------------------------------------------- responses

const PAGES = {
  home: () => ({ title: "Thieves Trap — Android Anti-Theft & Phone Security", active: "home",
    desc: "Thieves Trap alerts you by SMS with GPS location, photographs intruders, and lets you find, ring or lock your Android phone from any other phone.",
    body: landingContent() }),
  terms: () => ({ title: "Terms of Use — Thieves Trap", active: "terms", desc: "Terms of Use for the Thieves Trap Android app (English and Arabic).", body: termsContent() }),
  privacy: () => ({ title: "Privacy Policy — Thieves Trap", active: "privacy", desc: "Privacy Policy for the Thieves Trap Android app (English and Arabic).", body: privacyContent() }),
};

export function sitePage(name) {
  const p = PAGES[name]();
  const n = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
  return new Response(shell(p.title, p.desc, p.active, p.body, n), {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "public, max-age=300",
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
      "referrer-policy": "strict-origin-when-cross-origin",
      "content-security-policy":
        "default-src 'none'; script-src 'nonce-" + n + "'; style-src 'nonce-" + n +
        "'; img-src data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    },
  });
}
