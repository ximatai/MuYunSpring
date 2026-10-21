import type { FormulaExpressionFunction } from './formulaExpressionTokens';

/** Authoring uses the server parser; this UI never interprets formula source. */
export interface FormulaExpressionTools {
  loadFunctions(): Promise<readonly FormulaExpressionFunction[]>;
  validate(expression: string): Promise<void>;
}
