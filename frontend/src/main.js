import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import './style.css';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import App from './App.vue';
import router from './router';
import { useAuthStore } from '@/stores/auth';

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
// 登录态须在 router 安装前同步恢复，否则守卫在首次导航时误踢到 /login
useAuthStore().restore();
app.use(ElementPlus, { locale: zhCn });
for (const [key, comp] of Object.entries(ElementPlusIconsVue)) app.component(key, comp);
app.use(router);
app.mount('#app');
