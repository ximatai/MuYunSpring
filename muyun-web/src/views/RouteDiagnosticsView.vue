<script setup lang="ts">
import {
  useLanguageContext,
  resolveOperationErrorMessage,
  type OperationErrorMessage,
} from '@muyun/web-core';
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useRouteDiagnosticsStore } from '@/app/routeDiagnosticsStore';

const language = useLanguageContext();

const route = useRoute();
const diagnostics = useRouteDiagnosticsStore();
const menuId = computed(() =>
  typeof route.query._muyunMenuId === 'string' ? route.query._muyunMenuId : undefined,
);
const issues = computed(() => diagnostics.findIssues(menuId.value, route.path));
function diagnosticText(original: string, source?: OperationErrorMessage): string {
  if (source && source.message !== original) return original;
  return resolveOperationErrorMessage(
    { name: 'RouteConfigurationIssue', message: original, textMessage: source },
    language,
  );
}
</script>

<template>
  <section class="route-diagnostics" data-testid="route-diagnostics">
    <template v-if="issues.length">
      <h1>
        {{
          language.t(
            'platform.screen.RouteDiagnosticsView.page-entry-configuration-error',
            '页面入口配置错误',
          )
        }}
      </h1>
      <p>
        {{ language.t('platform.screen.RouteDiagnosticsView.requested-address', '请求地址：')
        }}{{ route.fullPath }}
      </p>
      <article v-for="issue in issues" :key="`${issue.code}:${issue.menuId ?? ''}`">
        <h2>
          {{
            issue.menuTitle ??
            language.t('platform.screen.RouteDiagnosticsView.static-route-declaration', '静态路由声明')
          }}
        </h2>
        <p>{{ diagnosticText(issue.reason, issue.reasonText) }}</p>
        <p v-if="issue.suggestion">
          {{ language.t('platform.screen.RouteDiagnosticsView.suggested-change', '修改建议：')
          }}{{ diagnosticText(issue.suggestion ?? '', issue.suggestionText) }}
        </p>
        <dl>
          <template v-if="issue.actual">
            <dt>
              {{
                language.t('platform.screen.RouteDiagnosticsView.actual-server-configuration', '后台实际配置')
              }}
            </dt>
            <dd>{{ issue.actual }}</dd>
          </template>
          <template v-if="issue.expected">
            <dt>
              {{
                language.t('platform.screen.RouteDiagnosticsView.frontend-route-requirements', '前端路由要求')
              }}
            </dt>
            <dd>{{ issue.expected }}</dd>
          </template>
        </dl>
      </article>
    </template>
    <template v-else>
      <h1>{{ language.t('platform.screen.RouteDiagnosticsView.404-page-not-found', '404 页面不存在') }}</h1>
      <p>
        {{ language.t('platform.screen.RouteDiagnosticsView.requested-address', '请求地址：')
        }}{{ route.fullPath }}
      </p>
      <p>
        {{
          language.t(
            'platform.screen.RouteDiagnosticsView.this-address-is-not-configured-in-the-current-user-s',
            '当前用户菜单没有配置这个地址，前端也没有注册这个路由。',
          )
        }}
      </p>
    </template>
  </section>
</template>

<style scoped>
.route-diagnostics {
  display: grid;
  gap: 14px;
  max-width: 760px;
  margin: 48px auto;
  padding: 24px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
  color: var(--muyun-support-text);
}
h1,
h2,
p {
  margin: 0;
}
article {
  display: grid;
  gap: 8px;
  padding: 14px;
  border-radius: 6px;
  background: var(--muyun-warning-soft);
}
dl {
  display: grid;
  gap: 4px;
  margin: 0;
}
dd {
  margin: 0;
  white-space: pre-wrap;
}
</style>
