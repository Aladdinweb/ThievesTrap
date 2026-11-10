// Public website for Thieves Trap, served by the license Worker (landing page, Terms, Privacy).
// Static, no admin code and no admin links: the admin panel is a separate Worker (admin_worker/).
// Every page is self-contained (inline CSS/JS under a per-request nonce, no external requests).

const SUPPORT_URL = "https://www.facebook.com/share/1D2RrP4ey2/";
const MESSENGER_URL = "https://m.me/61576473907901";
const RELEASES_URL = "https://github.com/Aladdinweb/ThievesTrap/releases";
const UPDATED = { en: "10 November 2026", ar: "10 نوفمبر 2026" };

const SHIELD_PATHS = '<path fill="#000000" fill-opacity="0.20" d="M54,97C54,97 16,80 16,55V30L54,16L92,30V55C92,80 54,97 54,97Z" transform="translate(2 3)"/><path fill="#1A1A1A" d="M54,94C54,94 18,77 18,53V31L54,18L90,31V53C90,77 54,94 54,94Z"/><path fill="#2D2D2D" d="M54,91C54,91 21,75 21,52V33L54,21L87,33V52C87,75 54,91 54,91Z"/><path fill="#8B0000" d="M54,89C54,89 23,73 23,51V34L54,22L85,34V51C85,73 54,89 54,89Z"/><path fill="#CC0000" d="M54,86C54,86 25,71 25,50V36L54,25L83,36V50C83,71 54,86 54,86Z"/><path fill="#FF2222" d="M54,25L83,36V44C83,44 68,38 54,38C40,38 25,44 25,44V36Z"/><path fill="#FFFFFF" fill-opacity="0.13" d="M54,26L80,36V42C72,39 63,37 54,37C45,37 36,39 28,42V36Z"/><path fill="#AA0000" d="M54,82C54,82 29,68 29,49V39L54,29L79,39V49C79,68 54,82 54,82Z"/><path fill="#FF4444" fill-opacity="0.27" d="M54,29L79,39V43C71,40 63,38 54,38C45,38 37,40 29,43V39Z"/><path fill="#FF0000" fill-opacity="0.53" d="M25,36L25,51C25,60 29,68 34,74L34,36Z"/><path fill="#FF0000" fill-opacity="0.33" d="M83,36L83,51C83,60 79,68 74,74L74,36Z"/><path fill="#FFFFFF" d="M37,54L44,62L70,36L76,42L44,74L31,60Z"/><path fill="#000000" fill-opacity="0.20" d="M38,56L44,63L70,38L75,43L44,75L32,62Z"/><path fill="#FFFFFF" fill-opacity="0.10" d="M42,26C46,24 50,23 54,23C58,23 62,24 66,26L54,22Z"/>';
const SHIELD = '<svg viewBox="0 0 108 108" role="img" aria-label="Thieves Trap">' + SHIELD_PATHS + '</svg>';
const FAVICON = "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">' + SHIELD_PATHS + '</svg>');

// Bilingual helpers: one element per language, CSS shows the active one.
const bi = (tag, en, ar, cls = "") =>
  `<${tag} class="en ${cls}">${en}</${tag}><${tag} class="ar ${cls}" lang="ar" dir="rtl">${ar}</${tag}>`;
const sp = (en, ar) => `<span class="en">${en}</span><span class="ar" lang="ar" dir="rtl">${ar}</span>`;

const CSS = `
:root{--bg:#050505;--card:#0d0d10;--line:#202027;--tx:#f4f4f5;--mu:#a0a0a8;--red:#cc0000;--red2:#ff2a2a;--grn:#00ff66}
*{box-sizing:border-box}
html{scroll-behavior:smooth;-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--tx);font:16px/1.65 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Arabic",Tahoma,sans-serif}
html[data-l="en"] .ar,html[data-l="ar"] .en{display:none!important}
a{color:inherit;text-decoration:none}
.wrap{max-width:1040px;margin:0 auto;padding:0 20px}
.top{position:sticky;top:0;z-index:10;background:rgba(5,5,5,.9);-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
.bar{display:flex;align-items:center;gap:16px;height:60px}
.brand{display:flex;align-items:center;gap:10px;font-weight:800;letter-spacing:.05em}
.brand svg{width:34px;height:34px}
.nav{display:flex;gap:20px;margin-inline-start:auto;color:var(--mu);font-size:14px}
.nav a:hover{color:var(--tx)}
.lang{display:flex;gap:4px}
.lang button{background:var(--card);color:var(--mu);border:1px solid var(--line);border-radius:8px;padding:6px 10px;font:600 13px system-ui,sans-serif;cursor:pointer}
.lang button[aria-pressed="true"]{color:#03140a;background:var(--grn);border-color:var(--grn)}
.hero{padding:56px 0 36px;display:grid;gap:32px;grid-template-columns:1.25fr .75fr;align-items:center}
.eyebrow{color:var(--grn);font-weight:700;font-size:12px;letter-spacing:.14em;text-transform:uppercase}
h1{font-size:clamp(34px,6vw,58px);line-height:1.1;margin:10px 0 16px;letter-spacing:-.02em}
h1 em{font-style:normal;color:var(--red2)}
.lead{color:var(--mu);font-size:18px;max-width:580px;margin:0}
.cta{display:flex;flex-wrap:wrap;gap:12px;margin:26px 0 12px;align-items:center}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:10px;padding:14px 22px;border-radius:12px;font-weight:700;border:1px solid transparent;cursor:pointer}
.btn-dl{background:var(--grn);color:#03140a}
.btn-dl:hover{background:#4dffa6}
.btn-red{background:var(--red);color:#fff}
.btn-red:hover{background:var(--red2)}
.btn-ghost{border-color:var(--line);background:var(--card)}
.btn-ghost:hover{border-color:#34343d}
.play{display:inline-flex;align-items:center;gap:12px;padding:9px 16px;border:1px solid var(--line);border-radius:12px;background:var(--card);opacity:.55;cursor:not-allowed;user-select:none}
.play small{display:block;font-size:10px;letter-spacing:.1em;color:var(--mu);text-transform:uppercase;line-height:1.2}
.play b{font-size:17px;line-height:1.2}
.note{font-size:13px;color:var(--mu);margin:0}
.meta{color:var(--mu);font-size:13px;margin:10px 0 0}
.shieldbox{display:flex;justify-content:center}
.shieldbox svg{width:min(80%,300px);height:auto;filter:drop-shadow(0 0 38px rgba(204,0,0,.55))}
section{padding:44px 0}
h2{font-size:clamp(24px,4vw,34px);margin:0 0 8px;letter-spacing:-.01em;line-height:1.2}
.sub{color:var(--mu);margin:0 0 26px;max-width:640px}
.grid{display:grid;gap:14px;grid-template-columns:repeat(auto-fit,minmax(240px,1fr))}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px}
.card.r{border-top:3px solid var(--red)}
.card.g{border-top:3px solid var(--grn)}
.card h3{margin:0 0 6px;font-size:17px}
.card p{margin:0;color:var(--mu);font-size:15px}
.price{font-size:34px;font-weight:800;line-height:1.2}
.price small{font-size:14px;color:var(--mu);font-weight:500}
.badge{display:inline-block;background:var(--grn);color:#03140a;font-weight:800;font-size:11px;letter-spacing:.08em;padding:3px 10px;border-radius:999px;margin-bottom:8px}
ul.tick{list-style:none;padding:0;margin:12px 0 0}
ul.tick li{padding-inline-start:22px;position:relative;margin:7px 0;color:var(--mu);font-size:15px}
ul.tick li::before{content:"";position:absolute;inset-inline-start:0;top:.6em;width:9px;height:9px;border-radius:50%;background:var(--grn)}
ol.steps{counter-reset:s;list-style:none;padding:0;margin:0;display:grid;gap:12px}
ol.steps li{counter-increment:s;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;padding-inline-start:62px;position:relative;color:var(--mu)}
ol.steps li::before{content:counter(s);position:absolute;inset-inline-start:16px;top:14px;width:30px;height:30px;border-radius:50%;background:var(--red);color:#fff;font-weight:800;display:grid;place-items:center}
.center{text-align:center}
.foot{border-top:1px solid var(--line);margin-top:30px;padding:26px 0 40px;color:var(--mu);font-size:14px;display:flex;flex-wrap:wrap;gap:10px 24px;justify-content:space-between}
.foot nav{display:flex;flex-wrap:wrap;gap:6px 20px}
.foot a:hover{color:var(--tx)}
.doc{max-width:780px;margin:0 auto;padding:40px 20px 20px}
.doc h1{font-size:clamp(28px,5vw,40px);margin:0 0 4px}
.doc h2{font-size:18px;margin:28px 0 6px;color:var(--grn)}
.doc p,.doc li{color:#cfcfd6;margin:0 0 10px}
.doc ul{padding-inline-start:20px;margin:0 0 10px}
.doc .stamp{color:var(--mu);font-size:14px;margin-bottom:18px}
.back{display:inline-block;color:var(--mu);font-size:14px;margin-bottom:16px}
@media(max-width:760px){.nav{display:none}.lang{margin-inline-start:auto}.hero{grid-template-columns:1fr;padding-top:28px}.shieldbox{order:-1}.shieldbox svg{width:140px}}
`;

const HEAD_JS = `(function(){var l="";try{l=localStorage.getItem("tt_lang")||""}catch(e){}if(l!=="ar"&&l!=="en")l=(navigator.language||"").slice(0,2)==="ar"?"ar":"en";var h=document.documentElement;h.setAttribute("data-l",l);h.lang=l;h.dir=l==="ar"?"rtl":"ltr"})();`;

const BODY_JS = `(function(){
var h=document.documentElement;
function mark(l){var b=document.querySelectorAll(".lang button");for(var i=0;i<b.length;i++){b[i].setAttribute("aria-pressed",b[i].getAttribute("data-set")===l?"true":"false")}}
mark(h.getAttribute("data-l"));
var bs=document.querySelectorAll(".lang button");
for(var i=0;i<bs.length;i++){bs[i].addEventListener("click",function(){var l=this.getAttribute("data-set");h.setAttribute("data-l",l);h.lang=l;h.dir=l==="ar"?"rtl":"ltr";mark(l);try{localStorage.setItem("tt_lang",l)}catch(e){}})}
var v=document.getElementById("ver");
if(v){fetch("/version").then(function(r){return r.json()}).then(function(j){if(j&&j.latest_version_name){v.textContent="v"+j.latest_version_name}}).catch(function(){})}
})();`;

function header(home) {
  const base = home ? "" : "/";
  return `<header class="top"><div class="wrap bar">
<a class="brand" href="/">${SHIELD}<span>THIEVES TRAP</span></a>
<nav class="nav">
<a href="${base}#features">${sp("Features", "المزايا")}</a>
<a href="${base}#pricing">${sp("Plans", "الباقات")}</a>
<a href="${base}#install">${sp("Install", "التثبيت")}</a>
<a href="${base}#support">${sp("Support", "الدعم")}</a>
</nav>
<div class="lang"><button type="button" data-set="en" aria-pressed="true">EN</button><button type="button" data-set="ar" aria-pressed="false">عربي</button></div>
</div></header>`;
}

function footer() {
  return `<footer class="wrap foot">
<span>© 2026 iLine Tech by Ferak Aladdin</span>
<nav>
<a href="/terms">${sp("Terms of Use", "شروط الاستخدام")}</a>
<a href="/privacy">${sp("Privacy Policy", "سياسة الخصوصية")}</a>
<a href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">${sp("Support", "الدعم")}</a>
<a href="${RELEASES_URL}" target="_blank" rel="noopener noreferrer">GitHub</a>
</nav></footer>`;
}

function shell(title, desc, body, n) {
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
${body}
<script nonce="${n}">${BODY_JS}</script>
</body></html>`;
}

// ---------------------------------------------------------------- landing

function feature(cls, en1, ar1, en2, ar2) {
  return `<div class="card ${cls}">${bi("h3", en1, ar1)}${bi("p", en2, ar2)}</div>`;
}

function plan(cls, badge, nameEn, nameAr, price, daysEn, daysAr) {
  return `<div class="card ${cls}">${badge ? `<span class="badge">${sp("BEST VALUE", "الأفضل قيمة")}</span>` : ""}
<h3>${sp(nameEn, nameAr)}</h3><div class="price">${price} <small>€</small></div><p>${sp(daysEn, daysAr)}</p></div>`;
}

function landingBody() {
  return `${header(true)}
<main>
<div class="wrap hero">
<div>
<div class="eyebrow">${sp("Android anti-theft &amp; phone security", "الحماية من السرقة وأمان الهاتف لأندرويد")}</div>
<h1>${sp("Your phone is <em>safe</em> with us.", "هاتفك <em>في أمان</em> معنا.")}</h1>
${bi("p", "If someone steals your phone or tries to unlock it, Thieves Trap alerts you by SMS with a GPS location, photographs the intruder, and lets you find, ring or lock the phone from any other phone.",
"إذا سُرق هاتفك أو حاول أحدهم فتحه، يرسل لك Thieves Trap تنبيهاً برسالة SMS مع موقعك عبر GPS، ويلتقط صورة للمتسلل، ويتيح لك تحديد مكان الهاتف أو تشغيل صفارة الإنذار أو قفله من أي هاتف آخر.", "lead")}
<div class="cta">
<a class="btn btn-dl" href="/download" rel="nofollow">${sp("Download APK", "تحميل ملف APK")}</a>
<a class="btn btn-ghost" href="#install">${sp("How to install", "طريقة التثبيت")}</a>
</div>
<div class="cta">
<div class="play" aria-disabled="true"><svg width="26" height="26" viewBox="0 0 24 24" aria-hidden="true"><path fill="#00ff66" d="M3.6 2.2 14 12 3.6 21.8c-.4-.2-.6-.6-.6-1.1V3.3c0-.5.2-.9.6-1.1z"/><path fill="#cc0000" d="M14 12 17.4 8.8l3.5 2c.9.5.9 1.9 0 2.4l-3.5 2z"/><path fill="#a0a0a8" d="M3.6 2.2c.3-.2.8-.2 1.2.1L17.4 8.8 14 12z"/><path fill="#a0a0a8" opacity=".7" d="M14 12l3.4 3.2L4.8 21.7c-.4.3-.9.3-1.2.1z"/></svg><span><small>${sp("Get it on", "حمّله من")}</small><b>Google Play</b></span></div>
<p class="note">${sp("Not available on the store yet, coming soon", "غير متوفر حالياً في المتجر، قريباً")}</p>
</div>
<p class="meta">${sp("Free plan included · Updates inside the app · Latest version:", "باقة مجانية متاحة · التحديثات داخل التطبيق · أحدث إصدار:")} <span id="ver">…</span></p>
</div>
<div class="shieldbox">${SHIELD}</div>
</div>

<section id="features"><div class="wrap">
${bi("h2", "Built to catch thieves, simple for you", "مصمم لإيقاع اللصوص، وبسيط لك")}
${bi("p", "Everything runs on your phone and reaches only the people and the Telegram bot you choose.", "كل شيء يعمل على هاتفك ولا يصل إلا إلى الأشخاص وبوت تيليجرام الذين تختارهم.", "sub")}
<div class="grid">
${feature("r", "Intruder selfie", "صورة المتسلل", "Photographs anyone who enters a wrong PIN or pattern and sends the picture to your private Telegram bot (Full Protection).", "يلتقط صورة لكل من يُدخل رمزاً أو نمطاً خاطئاً ويرسلها إلى بوت تيليجرام الخاص بك (الحماية الكاملة).")}
${feature("g", "Remote SMS control", "التحكم عن بُعد بالرسائل", "Send WHERE, ALARM or LOCK from any phone to find, ring or lock your device. WHERE is included in the free plan.", "أرسل WHERE أو ALARM أو LOCK من أي هاتف لتحديد مكان جهازك أو تشغيل الإنذار أو قفله. أمر WHERE متاح في الباقة المجانية.")}
${feature("r", "SIM-swap trap", "فخ تبديل الشريحة", "If the SIM card is removed or changed, you receive one SMS with the new carrier and the phone's location.", "إذا أُزيلت شريحة الاتصال أو استُبدلت، تصلك رسالة واحدة بمشغّل الشبكة الجديد وموقع الهاتف.")}
${feature("g", "Watch Tether", "ربط الساعة الذكية", "Pair a Bluetooth smartwatch: if it disconnects, the phone locks and a countdown starts before an alert is sent.", "اربط ساعة بلوتوث: إذا انقطع الاتصال بها يُقفل الهاتف ويبدأ عدّ تنازلي قبل إرسال التنبيه.")}
${feature("r", "Survival Timer", "مؤقّت الأمان", "A dead-man's switch: if you do not check in on time, an emergency SMS with your location is sent.", "مفتاح الطوارئ: إن لم تؤكد سلامتك في الوقت المحدد تُرسل رسالة طوارئ بموقعك.")}
${feature("g", "Loud siren", "صفارة إنذار عالية", "Make the phone ring at maximum volume from a distance, and silence it the same way.", "شغّل الهاتف بأعلى صوت عن بُعد، وأوقفه بالطريقة نفسها.")}
${feature("r", "Private by design", "خصوصية أولاً", "No account needed. Your photos and location go straight to your own contacts and bot; we do not host them.", "لا حاجة لحساب. صورك وموقعك تصل مباشرة إلى جهات اتصالك وبوتك، ولا نستضيفها نحن.")}
${feature("g", "English, French &amp; Arabic", "الإنجليزية والفرنسية والعربية", "The whole app is available in three languages, including right-to-left Arabic.", "التطبيق كاملاً متوفر بثلاث لغات، بما فيها العربية من اليمين إلى اليسار.")}
</div></div></section>

<section id="pricing"><div class="wrap">
${bi("h2", "Free to start. Full Protection when you need it.", "ابدأ مجاناً. والحماية الكاملة عند الحاجة.")}
${bi("p", "Install the free version first. Unlock Full Protection with a license key.", "ثبّت النسخة المجانية أولاً. وافتح الحماية الكاملة بمفتاح ترخيص.", "sub")}
<div class="grid">
<div class="card"><h3>${sp("Free", "مجاني")}</h3>
<ul class="tick"><li>${sp("Basic protection and activity log", "حماية أساسية وسجل للأحداث")}</li><li>${sp("Remote location by SMS (WHERE)", "تحديد الموقع عن بُعد برسالة (WHERE)")}</li></ul></div>
<div class="card g"><h3>${sp("Full Protection", "الحماية الكاملة")}</h3>
<ul class="tick"><li>${sp("Intruder selfies and Telegram alerts", "صور المتسلل وتنبيهات تيليجرام")}</li><li>${sp("All remote commands: ALARM, LOCK, INFO, BATTERY…", "كل أوامر التحكم: ALARM وLOCK وINFO وBATTERY…")}</li><li>${sp("Full Theft Mode, Watch Tether, second emergency contact", "وضع السرقة الكامل وربط الساعة وجهة اتصال طوارئ ثانية")}</li></ul></div>
</div>
<div class="grid">
${plan("", false, "Monthly", "شهري", "5.99", "30 days from activation", "30 يوماً من التفعيل")}
${plan("", false, "3 Months", "3 أشهر", "10.99", "90 days from activation", "90 يوماً من التفعيل")}
${plan("g", true, "Annual", "سنوي", "24.99", "365 days from activation", "365 يوماً من التفعيل")}
</div>
<p class="center"><a class="btn btn-red" href="${MESSENGER_URL}" target="_blank" rel="noopener noreferrer">${sp("Get a key on Messenger", "احصل على مفتاح عبر ماسنجر")}</a></p>
${bi("p", "One key activates one phone. Keys do not renew automatically. Prices in euros.", "المفتاح الواحد يفعّل هاتفاً واحداً. المفاتيح لا تتجدد تلقائياً. الأسعار باليورو.", "note center")}
</div></section>

<section id="install"><div class="wrap">
${bi("h2", "Install in three steps", "التثبيت في ثلاث خطوات")}
${bi("p", "Thieves Trap is installed from an APK file until it is available on Google Play.", "يُثبَّت Thieves Trap من ملف APK إلى أن يتوفر على متجر Google Play.", "sub")}
<ol class="steps">
<li>${sp("Tap <b>Download APK</b> above and open the downloaded file.", "اضغط <b>تحميل ملف APK</b> في الأعلى ثم افتح الملف الذي تم تنزيله.")}</li>
<li>${sp("If Android asks, allow “Install unknown apps” for your browser or file manager, then tap Install.", "إذا طلب أندرويد ذلك، اسمح بخيار «تثبيت التطبيقات غير المعروفة» لمتصفحك أو مدير الملفات، ثم اضغط تثبيت.")}</li>
<li>${sp("Open Thieves Trap, set your PIN and emergency contact, grant the permissions and tap ARM.", "افتح Thieves Trap، وحدّد رمز PIN وجهة اتصال الطوارئ، وامنح الأذونات ثم اضغط ARM.")}</li>
</ol>
</div></section>

<section id="support"><div class="wrap center">
${bi("h2", "Need help or a license key?", "تحتاج مساعدة أو مفتاح ترخيص؟")}
${bi("p", "Message us on our Facebook page.", "راسلنا عبر صفحتنا على فيسبوك.", "sub")}
<a class="btn btn-ghost" href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">${sp("Contact support on Facebook", "تواصل مع الدعم على فيسبوك")}</a>
</div></section>
</main>
${footer()}`;
}

// ---------------------------------------------------------------- legal pages

const sec = (hEn, hAr, pEn, pAr) => `${bi("h2", hEn, hAr)}${bi("p", pEn, pAr)}`;
const secList = (hEn, hAr, introEn, introAr, itemsEn, itemsAr) =>
  `${bi("h2", hEn, hAr)}${bi("p", introEn, introAr)}<ul class="en">${itemsEn.map((x) => `<li>${x}</li>`).join("")}</ul><ul class="ar" lang="ar" dir="rtl">${itemsAr.map((x) => `<li>${x}</li>`).join("")}</ul>`;

function docBody(titleEn, titleAr, inner) {
  return `${header(false)}
<main class="doc">
<a class="back" href="/">${sp("← Back to home", "← العودة إلى الرئيسية")}</a>
${bi("h1", titleEn, titleAr)}
<p class="stamp en">Thieves Trap by iLine Tech · Last updated ${UPDATED.en}</p><p class="stamp ar" lang="ar" dir="rtl">Thieves Trap من iLine Tech · آخر تحديث ${UPDATED.ar}</p>
${inner}
</main>
${footer()}`;
}

function termsBody() {
  return docBody("Terms of Use", "شروط الاستخدام",
    sec("1. Acceptance", "1. القبول", "By installing or using Thieves Trap you agree to these Terms of Use and to our Privacy Policy. If you do not agree, please do not use the app.",
      "بتثبيت Thieves Trap أو استخدامه فإنك توافق على شروط الاستخدام هذه وعلى سياسة الخصوصية. إذا كنت لا توافق فلا تستخدم التطبيق.") +
    sec("2. What the app does", "2. ما يقدمه التطبيق", "Thieves Trap is an Android anti-theft and phone-safety app. It can send SMS alerts with a GPS location, take photos of intruders, and respond to remote SMS commands that you authorise.",
      "Thieves Trap تطبيق أندرويد للحماية من السرقة وأمان الهاتف. يمكنه إرسال تنبيهات SMS مع موقع GPS، والتقاط صور للمتسللين، والاستجابة لأوامر SMS عن بُعد التي تأذن بها.") +
    sec("3. Lawful and consensual use", "3. الاستخدام المشروع وبموافقة الأطراف", "Use the app only on devices you own or are authorised to protect. You must not use it to monitor, track or photograph another person without their knowledge and consent. You are responsible for complying with the laws that apply to you.",
      "استخدم التطبيق فقط على أجهزة تملكها أو مخوّل بحمايتها. يُمنع استخدامه لمراقبة شخص آخر أو تتبعه أو تصويره دون علمه وموافقته. أنت مسؤول عن الالتزام بالقوانين السارية عليك.") +
    sec("4. Permissions", "4. الأذونات", "To work, the app needs Device Administrator, Camera, Location, SMS and Bluetooth access. You can withdraw a permission at any time in Android settings, but the related protection features will then stop working.",
      "لكي يعمل التطبيق يحتاج إلى صلاحيات مشرف الجهاز والكاميرا والموقع والرسائل والبلوتوث. يمكنك سحب أي إذن في أي وقت من إعدادات أندرويد، لكن ميزات الحماية المرتبطة به ستتوقف.") +
    sec("5. Licenses and plans", "5. التراخيص والباقات", "Full Protection is unlocked with a license key (Monthly 30 days, 3 Months 90 days, Annual 365 days, or a trial). A key works on one device at a time, is checked online and is tied to that device. Its period starts at the first activation, and keys do not renew automatically. A key can be revoked if it is shared, resold or obtained fraudulently.",
      "تُفتح الحماية الكاملة بمفتاح ترخيص (شهري 30 يوماً، 3 أشهر 90 يوماً، سنوي 365 يوماً، أو نسخة تجريبية). يعمل المفتاح على جهاز واحد في كل مرة، ويُتحقق منه عبر الإنترنت ويرتبط بذلك الجهاز. تبدأ مدته عند أول تفعيل، ولا تتجدد المفاتيح تلقائياً. يجوز إلغاء المفتاح إذا تمت مشاركته أو إعادة بيعه أو الحصول عليه بطريقة احتيالية.") +
    sec("6. Costs and network", "6. التكاليف والشبكة", "Alerts use SMS and, where enabled, the internet. Your carrier may charge for messages. Alerts depend on signal, GPS, battery and the phone being switched on.",
      "تعتمد التنبيهات على الرسائل القصيرة وعلى الإنترنت عند تفعيله. قد يفرض مشغّل الشبكة رسوماً على الرسائل. وتعتمد التنبيهات على الإشارة وGPS والبطارية وعلى كون الهاتف قيد التشغيل.") +
    sec("7. No guarantee", "7. عدم وجود ضمان", "Thieves Trap helps you locate and respond, but we cannot guarantee that a phone will be recovered or that every alert will be delivered. The app is provided “as is”.",
      "يساعدك Thieves Trap على تحديد المكان والاستجابة، لكننا لا نضمن استرجاع الهاتف ولا وصول كل تنبيه. يُقدَّم التطبيق «كما هو».") +
    sec("8. Limitation of liability", "8. حدود المسؤولية", "To the extent permitted by law, iLine Tech is not liable for indirect or consequential loss, or for loss caused by carrier, network or device limitations.",
      "في الحدود التي يسمح بها القانون، لا تتحمل iLine Tech مسؤولية الخسائر غير المباشرة أو التبعية، ولا الخسائر الناتجة عن قيود المشغّل أو الشبكة أو الجهاز.") +
    sec("9. Updates", "9. التحديثات", "The app can check for and download updates from our GitHub releases. Some updates may be mandatory so that security fixes and license checks keep working. Your protection keeps running in the background while you update.",
      "يمكن للتطبيق البحث عن التحديثات وتنزيلها من إصداراتنا على GitHub. قد تكون بعض التحديثات إلزامية ليستمر عمل إصلاحات الأمان وفحص التراخيص. تستمر حمايتك في العمل في الخلفية أثناء التحديث.") +
    `${bi("h2", "10. Changes and contact", "10. التعديلات والتواصل")}${bi("p", `We may update these terms; the date above shows the latest revision. Questions: <a href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">our Facebook page</a>.`, `قد نحدّث هذه الشروط، ويبيّن التاريخ أعلاه آخر مراجعة. للاستفسار: <a href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">صفحتنا على فيسبوك</a>.`)}`);
}

function privacyBody() {
  return docBody("Privacy Policy", "سياسة الخصوصية",
    sec("1. In short", "1. باختصار", "Your photos, location and settings stay on your phone and are sent only to the emergency contacts and the private Telegram bot you choose. We do not host or store them on our servers.",
      "تبقى صورك وموقعك وإعداداتك على هاتفك، ولا تُرسل إلا إلى جهات اتصال الطوارئ وبوت تيليجرام الخاص الذي تختاره. نحن لا نستضيفها ولا نخزنها على خوادمنا.") +
    sec("2. Data on your device", "2. البيانات على جهازك", "Your PIN, emergency contacts, settings, activity log and intruder photos are stored locally in the app's private storage. Uninstalling the app removes them.",
      "يُخزَّن رمز PIN وجهات اتصال الطوارئ والإعدادات وسجل الأحداث وصور المتسللين محلياً في مساحة التطبيق الخاصة. وحذف التطبيق يزيلها.") +
    sec("3. Data sent from your phone", "3. البيانات المرسلة من هاتفك", "SMS alerts (location, battery and device details such as SIM and IMEI information) go to the contacts you set, through your carrier. If you connect a Telegram bot, messages and intruder photos go to that bot through Telegram.",
      "تُرسل تنبيهات SMS (الموقع والبطارية وتفاصيل الجهاز مثل معلومات الشريحة وIMEI) إلى جهات الاتصال التي حددتها عبر مشغّل الشبكة. وإذا ربطت بوت تيليجرام فستُرسل الرسائل وصور المتسللين إلى ذلك البوت عبر تيليجرام.") +
    sec("4. License server", "4. خادم التراخيص", "When you activate a key, and about every 15 minutes while online, the app contacts our license server (hosted on Cloudflare). It sends the license key, an Android device identifier used as a label, a public key generated on your phone, and timestamps. The server keeps the key's plan, dates, device label and status. It never receives your location, photos, contacts or messages.",
      "عند تفعيل مفتاح، وكل 15 دقيقة تقريباً أثناء الاتصال بالإنترنت، يتصل التطبيق بخادم التراخيص لدينا (المستضاف على Cloudflare). يرسل مفتاح الترخيص ومعرّف جهاز أندرويد يُستخدم كوسم ومفتاحاً عاماً يُنشأ على هاتفك وبصمات زمنية. يحتفظ الخادم بباقة المفتاح وتواريخه ووسم الجهاز وحالته. ولا يستلم أبداً موقعك أو صورك أو جهات اتصالك أو رسائلك.") +
    sec("5. Updates and this website", "5. التحديثات وهذا الموقع", "Checking for updates reads a small file from GitHub, and the APK is downloaded from GitHub Releases. Cloudflare and GitHub may log technical data such as your IP address under their own policies. This website has no cookies or analytics; it only remembers your language choice in your browser.",
      "يقرأ فحص التحديثات ملفاً صغيراً من GitHub، ويُنزَّل ملف APK من إصدارات GitHub. قد تسجّل Cloudflare وGitHub بيانات تقنية مثل عنوان IP وفق سياساتها. لا يستخدم هذا الموقع ملفات تعريف الارتباط ولا أدوات تحليل، ويحفظ فقط اختيارك للغة في متصفحك.") +
    secList("6. Permissions", "6. الأذونات", "The app asks for these permissions, each used only for its protection feature:", "يطلب التطبيق هذه الأذونات، ويُستخدم كل منها لميزة الحماية الخاصة به فقط:",
      ["Device Administrator: detect failed unlock attempts and lock the screen", "Camera: intruder selfies", "Location: GPS position in alerts", "SMS: send alerts and receive your remote commands", "Bluetooth: Watch Tether"],
      ["مشرف الجهاز: رصد محاولات الفتح الفاشلة وقفل الشاشة", "الكاميرا: صور المتسللين", "الموقع: موقع GPS في التنبيهات", "الرسائل: إرسال التنبيهات واستقبال أوامرك عن بُعد", "البلوتوث: ربط الساعة الذكية"]) +
    sec("7. Retention and deletion", "7. الاحتفاظ والحذف", "License records are kept while the key exists. To have a record deleted, contact support. Data on your phone is yours to delete at any time from the app or by uninstalling it.",
      "تُحفظ سجلات التراخيص ما دام المفتاح قائماً. لطلب حذف سجل تواصل مع الدعم. أما البيانات على هاتفك فهي لك، يمكنك حذفها في أي وقت من التطبيق أو بحذفه.") +
    `${bi("h2", "8. Changes and contact", "8. التعديلات والتواصل")}${bi("p", `We may update this policy; the date above shows the latest revision. Questions: <a href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">our Facebook page</a>.`, `قد نحدّث هذه السياسة، ويبيّن التاريخ أعلاه آخر مراجعة. للاستفسار: <a href="${SUPPORT_URL}" target="_blank" rel="noopener noreferrer">صفحتنا على فيسبوك</a>.`)}`);
}

// ---------------------------------------------------------------- responses

const PAGES = {
  home: () => ({
    title: "Thieves Trap — Android Anti-Theft & Phone Security",
    desc: "Thieves Trap alerts you by SMS with GPS location, photographs intruders, and lets you find, ring or lock your Android phone from any other phone.",
    body: landingBody(),
  }),
  terms: () => ({ title: "Terms of Use — Thieves Trap", desc: "Terms of Use for the Thieves Trap Android app (English and Arabic).", body: termsBody() }),
  privacy: () => ({ title: "Privacy Policy — Thieves Trap", desc: "Privacy Policy for the Thieves Trap Android app (English and Arabic).", body: privacyBody() }),
};

export function sitePage(name) {
  const p = PAGES[name]();
  const n = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
  return new Response(shell(p.title, p.desc, p.body, n), {
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
