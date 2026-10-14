import type {
  ConstructionAcceptancePreview,
  ConstructionProgress,
  ConstructionTask,
  TextPresentationDocument,
} from '@muyun/web-contracts';
import { resolvePresentationText, type LanguageContext } from '@muyun/web-core';

function textAt(
  document: TextPresentationDocument,
  path: string,
  original: string,
  language: LanguageContext,
) {
  const text = document.textBindings?.[path];
  if (
    !text ||
    text.source.sourceKind !== 'CODE' ||
    !text.source.sourceId.startsWith('platform.construction.') ||
    !language.fallbackLanguageTags.value.some((tag) => text.languages[tag])
  )
    return original;
  return resolvePresentationText(text, language).text;
}

/** Typed display fields only. Identifiers, evidence status, fingerprints and user statements remain facts. */
export function constructionTaskPresentation(
  task: ConstructionTask,
  language: LanguageContext,
): ConstructionTask {
  return {
    ...task,
    unmappedRequirements: task.unmappedRequirements.map((item, index) => ({
      ...item,
      explanation: textAt(task, `/unmappedRequirements/${index}/explanation`, item.explanation, language),
    })),
    objects: task.objects.map((object, index) => ({
      ...object,
      options: object.options.map((option, optionIndex) => ({
        ...option,
        explanation: textAt(
          task,
          `/objects/${index}/options/${optionIndex}/explanation`,
          option.explanation,
          language,
        ),
      })),
      requirements: object.requirements.map((item, itemIndex) => ({
        ...item,
        explanation: textAt(
          task,
          `/objects/${index}/requirements/${itemIndex}/explanation`,
          item.explanation,
          language,
        ),
      })),
      ...(object.progress
        ? {
            progress: {
              ...object.progress,
              remainingWork: object.progress.remainingWork.map((line, lineIndex) =>
                textAt(task, `/objects/${index}/progress/remainingWork/${lineIndex}`, line, language),
              ),
            },
          }
        : {}),
    })),
  };
}
export function constructionProgressPresentation(
  progress: ConstructionProgress,
  language: LanguageContext,
): ConstructionProgress {
  return {
    ...progress,
    remainingWork: progress.remainingWork.map((line, index) =>
      textAt(progress, `/remainingWork/${index}`, line, language),
    ),
    ...(progress.requirements
      ? {
          requirements: progress.requirements.map((item, index) => ({
            ...item,
            explanation: textAt(progress, `/requirements/${index}/explanation`, item.explanation, language),
          })),
        }
      : {}),
  };
}
export function constructionAcceptancePresentation(
  preview: ConstructionAcceptancePreview,
  language: LanguageContext,
): ConstructionAcceptancePreview {
  return {
    ...preview,
    checks: preview.checks.map((line, index) => textAt(preview, `/checks/${index}`, line, language)),
  };
}
