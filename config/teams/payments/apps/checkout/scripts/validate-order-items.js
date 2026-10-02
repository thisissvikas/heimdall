export default function ({ response, assert }) {
  const items = response.json().items;
  assert(Array.isArray(items), 'items must be an array');
  assert(items.every(item => item.requiredField != null), 'all required fields must be present');
  return { itemCount: items.length };
}
