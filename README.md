## для разраба B

- Команды ядра: `IssueTicket.execute`, `IssueTicket.fromMobileAppointment`, `CancelTicket.execute`.
- Чтение: `TicketQueries`; DTO: `common-dto/TicketView` и `EventEnvelope`.
- Запрос кандидата: `TicketRepository.lockHead`; обязателен активный контекст транзакции. Он не назначает клиента окну.
- `ticket_events` и `outbox` пишутся синхронно в транзакции команды; публиковать `pending` должен будущий воркер B.
- Внутренний вход МП принимает `externalId`, повторяет исходный ответ и не открыт наружу.
- Для проигрывания новых доменных ошибок следует использовать `code` из `DomainException` и коды дизайн-документа.
