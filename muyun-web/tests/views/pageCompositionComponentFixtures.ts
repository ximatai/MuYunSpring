export const inputComponents = [
  { component: 'text', title: '单行文本', fieldSpecAlias: 'string' },
  { component: 'textarea', title: '多行文本', fieldSpecAlias: 'text' },
  { component: 'number', title: '数字', fieldSpecAlias: 'decimal' },
  { component: 'date', title: '日期', fieldSpecAlias: 'date' },
  { component: 'switch', title: '开关', fieldSpecAlias: 'boolean' },
] as const;
