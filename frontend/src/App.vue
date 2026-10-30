<template>
  <router-view />
</template>

<script setup>
// 应用壳：登录/未登录分流由 router 守卫负责（见 router/index.js）。
// 这里只保留：401 全局回调 + 页面版本检测（构建产物变化时提示刷新）。
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { onUnauthorized } from '@/api/http';

const router = useRouter();
const authStore = useAuthStore();

// 任何接口 401（凭据失效/被改）→ 清登录态并跳登录页
onUnauthorized(() => {
  authStore.logout();
  router.push('/login');
});

// 页面版本检测：构建产物变化时提示刷新。
// 比对线上 index.html 里 <script src="/assets/index-xxx.js"> 的哈希与当前页面加载的哈希，
// 任何一次重新构建都会改变哈希，无需手工维护版本号。
function currentAssetSrc() {
  const el = document.querySelector('script[src*="/assets/index-"]');
  return el ? el.getAttribute('src') : '';
}

function showStaleBanner() {
  if (document.getElementById('staleBar')) return;
  const bar = document.createElement('div');
  bar.id = 'staleBar';
  bar.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:99;background:#c94f4f;color:#fff;font-size:13px;padding:9px 14px;display:flex;align-items:center;justify-content:center;gap:12px;';
  const span = document.createElement('span');
  span.textContent = '页面代码已更新，当前标签页仍在运行旧版本（人工确认框等功能可能失效）。';
  const btn = document.createElement('button');
  btn.textContent = '立即刷新';
  btn.style.cssText = 'border:none;border-radius:6px;padding:4px 12px;font-size:12px;cursor:pointer;background:#fff;color:#c94f4f;font-weight:500;';
  btn.onclick = () => location.reload();
  bar.appendChild(span);
  bar.appendChild(btn);
  document.body.appendChild(bar);
}

async function checkPageVersion() {
  try {
    const res = await fetch('index.html?ts=' + Date.now(), { cache: 'no-store' });
    if (!res.ok) return;
    const txt = await res.text();
    const m = txt.match(/src="(\/assets\/index-[^"]+\.js)"/);
    const cur = currentAssetSrc();
    if (m && cur && m[1] !== cur) showStaleBanner();
  } catch (e) { /* 服务未起或离线时不打扰用户 */ }
}

onMounted(() => {
  setTimeout(checkPageVersion, 10000);
  setInterval(checkPageVersion, 60000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) checkPageVersion(); });
});
</script>
