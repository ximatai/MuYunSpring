import './styles.css';

import { createPinia } from 'pinia';
import { createApp } from 'vue';
import App from './App.vue';
import { VueQueryPlugin, queryClient } from './app/query';
import { router } from './app/router';

createApp(App).use(createPinia()).use(router).use(VueQueryPlugin, { queryClient }).mount('#app');
