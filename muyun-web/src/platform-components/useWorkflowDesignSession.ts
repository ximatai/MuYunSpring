import { ref } from 'vue';
import type { WorkflowDesign, WorkflowNode, WorkflowRoute } from '@muyun/web-contracts';
import { DraftUpdateRejectedError, OperationUsageError, useLanguageContext } from '@muyun/web-core';
import { useWorkflowDesignHistory } from './useWorkflowDesignHistory';
import { updateWorkflowRoute } from './workflowDesignEditing';
import {
  prepareWorkflowDesignOperations,
  type WorkflowDesignOperationBatch,
} from './workflowDesignOperations';

/** Shared draft ownership, independent of canvas, properties and assistant entry points. */
export function useWorkflowDesignSession() {
  const language = useLanguageContext();
  const design = ref<WorkflowDesign>({ nodes: [], links: [] });
  const history = useWorkflowDesignHistory(design);
  function updateNode(key: string, patch: Partial<WorkflowNode>) {
    design.value = {
      ...design.value,
      nodes: design.value.nodes.map((node) => (node.nodeKey === key ? { ...node, ...patch } : node)),
    };
  }
  function updateRoute(key: string, patch: Partial<WorkflowRoute>) {
    design.value = updateWorkflowRoute(design.value, key, patch, language);
  }
  function prepare(
    batch: WorkflowDesignOperationBatch,
    fields: Parameters<typeof prepareWorkflowDesignOperations>[2],
    current: () => boolean,
  ) {
    const checkpoint = history.checkpoint();
    const candidate = prepareWorkflowDesignOperations(design.value, batch, fields, language);
    return () => {
      if (!current() || !checkpoint())
        throw new DraftUpdateRejectedError(
          new OperationUsageError(
            language.t('platform.workflow-assistant.draft-changed', '流程草稿已变更，请重新读取'),
          ),
        );
      design.value = candidate.design;
      return { changes: candidate.changes, created: candidate.created, diagnostics: candidate.diagnostics };
    };
  }
  return { design, history, updateNode, updateRoute, prepare };
}
