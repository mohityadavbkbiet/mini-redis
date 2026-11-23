import { TYPE_ICON } from '../format.js';

export default function TypeBadge({ type }) {
  return (
    <span className="pill" style={{ '--c': `var(--t-${type})` }}>
      <i className={`bi bi-${TYPE_ICON[type] || 'box'}`} />{type}
    </span>
  );
}
