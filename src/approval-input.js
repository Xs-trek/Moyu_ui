const MAX_QUESTIONS = 4;
const MAX_OPTIONS = 20;

function boundedText(value, max) { return typeof value === 'string' && value.length > 0 && value.length <= max; }

/** Validate the bounded AskUserQuestion view shape without changing the original question keys. */
export function askUserQuestions(approval) {
  if (approval?.kind !== 'userInput' || approval?.tool !== 'AskUserQuestion') return null;
  const raw = approval.input?.questions;
  if (!Array.isArray(raw) || raw.length < 1 || raw.length > MAX_QUESTIONS) return null;
  const seen = new Set();
  const questions = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object' || !boundedText(item.question, 2000) || seen.has(item.question)) return null;
    seen.add(item.question);
    if (item.header !== undefined && !boundedText(item.header, 128)) return null;
    if (item.options !== undefined && (!Array.isArray(item.options) || item.options.length > MAX_OPTIONS)) return null;
    const options = [];
    for (const option of item.options || []) {
      if (!option || typeof option !== 'object' || !boundedText(option.label, 256)
          || (option.description !== undefined && !boundedText(option.description, 1000))) return null;
      options.push({ label: option.label, description: option.description || '' });
    }
    questions.push({ question: item.question, header: item.header || '', options, multiSelect: item.multiSelect === true });
  }
  return questions;
}

/** Build the only structured decision accepted from the AskUserQuestion form. */
export function askUserDecision(questions, answerLists) {
  if (!Array.isArray(questions) || !Array.isArray(answerLists) || questions.length !== answerLists.length) throw new Error('问题数据已变化，请刷新后重试');
  const answers = {};
  questions.forEach((question, index) => {
    const values = (answerLists[index] || []).filter((value) => typeof value === 'string' && value.trim())
      .map((value) => question.options.length ? value : value.trim());
    if (!values.length) throw new Error(`请回答：${question.header || question.question}`);
    if (!question.multiSelect && values.length !== 1) throw new Error(`“${question.header || question.question}”只能选择一项`);
    if (question.options.length && values.some((value) => !question.options.some((option) => option.label === value))) throw new Error('回答不在当前选项中，请重新选择');
    answers[question.question] = question.multiSelect ? [...new Set(values)] : values[0];
  });
  return { allowWithModification: { answers } };
}
