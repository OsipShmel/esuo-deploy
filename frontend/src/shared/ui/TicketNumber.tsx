import './components.css';

export function TicketNumber({ value }: { value: string }) {
  return <span className="ticket-number">{value}</span>;
}
