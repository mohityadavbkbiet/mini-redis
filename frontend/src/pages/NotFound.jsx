import { Link } from 'react-router-dom';
import { Empty, Panel } from '../components/Ui.jsx';

export default function NotFound() {
  return (
    <Panel>
      <Empty icon="compass" title="Page not found">That address does not exist. <Link to="/">Go to the dashboard</Link></Empty>
    </Panel>
  );
}
