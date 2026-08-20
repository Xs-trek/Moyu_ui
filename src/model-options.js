function normalized(value) {
  return typeof value === 'string' ? value.trim() : '';
}

/**
 * Returns only locally observed/certified model identifiers. It intentionally does
 * not pretend to be a provider model catalogue and never performs network probing.
 */
export function modelSuggestions(view, kind) {
  const values = [];
  const add = (value) => {
    const model = normalized(value);
    if (model && !values.some((entry) => entry.toLowerCase() === model.toLowerCase())) values.push(model);
  };
  const adapter = view?.server?.adapters?.find((entry) => entry.adapter === kind);
  const account = view?.accounts?.adapters?.find((entry) => entry.adapter === kind);
  if (view?.config?.defaultAdapter === kind) {
    add(view.config.model);
    add(view.config.effectiveModel);
  }
  add(adapter?.effectiveModel);
  add(account?.effectiveModel);
  (account?.profiles || []).forEach((profile) => add(profile.effectiveModel));
  (view?.sessions || []).filter((session) => session.kind === kind).forEach((session) => add(session.model));
  (adapter?.supportedModels || []).forEach(add);
  return values.slice(0, 12);
}

export function modelDisplay(value, effectiveModel) {
  const explicit = normalized(value);
  if (explicit) return explicit;
  const effective = normalized(effectiveModel);
  return effective ? `CLI 默认 · ${effective}` : 'CLI 默认模型';
}
