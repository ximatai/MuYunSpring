import 'dayjs/locale/zh-cn';
import 'dayjs/locale/fr';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import enUS from 'ant-design-vue/es/locale/en_US';
import frFR from 'ant-design-vue/es/locale/fr_FR';
import type { Locale } from 'ant-design-vue/es/locale-provider';
import { shallowRef } from 'vue';

/** UI adapter locale resources are registered at deployment composition. */
export const uiLanguageLocales = shallowRef<Readonly<Record<string, Locale>>>({
  'zh-CN': zhCN,
  en: enUS,
  fr: frFR,
});
export function registerUiLanguageLocales(locales: Record<string, Locale>) {
  uiLanguageLocales.value = { ...uiLanguageLocales.value, ...locales };
}
