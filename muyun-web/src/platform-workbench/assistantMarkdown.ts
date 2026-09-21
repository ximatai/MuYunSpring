import MarkdownIt from 'markdown-it';

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
  typographer: false,
});

markdown.validateLink = (url) => /^(?:https?:|mailto:)/i.test(url) || /^(?:#|\/|\.\.?\/|\?)/.test(url);

const defaultLinkOpen =
  markdown.renderer.rules.link_open ??
  ((tokens, index, options, _environment, renderer) => renderer.renderToken(tokens, index, options));

markdown.renderer.rules.link_open = (tokens, index, options, environment, renderer) => {
  const token = tokens[index];
  const href = token?.attrGet('href');
  if (href && /^https?:/i.test(href)) {
    token.attrSet('target', '_blank');
    token.attrSet('rel', 'noopener noreferrer');
  }
  return defaultLinkOpen(tokens, index, options, environment, renderer);
};

markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index]?.content ?? '');

export function renderAssistantMarkdown(content: string): string {
  return markdown.render(content);
}
