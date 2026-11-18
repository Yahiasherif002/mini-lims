# Mini LIMS

A small laboratory information management system for the sample-to-certificate workflow in a QC lab. Analysts
register samples and enter results, the system checks each result against the product specification, a reviewer
approves the results, and approved samples get a Certificate of Analysis generated with JasperReports. Every change
is recorded in an audit trail that the database itself refuses to edit or delete.

Built with Java 21, Spring Boot, Spring Data JPA, PostgreSQL, Flyway and JasperReports.

![Certificate of Analysis](docs/sample-certificate.png)

## Workflow

```mermaid
flowchart LR
    P[Product specification] --> R[Register sample]
    R --> E[Enter results<br/>manually or from instrument CSV]
    E --> O{Within limits?}
    O -- yes --> Pass
    O -- no --> OOS[Flag out of specification]
    Pass --> V[Reviewer approves or rejects]
    OOS --> V
    V -- reject with reason --> E
    V -- approve --> C[Certificate of Analysis PDF]
```

1. A reviewer defines a product and its specification: each test has a unit and a minimum, a maximum or both.
2. An analyst registers a sample for a batch. The sample gets one pending result per test, with the limits copied
   from the specification, so later changes to a specification never alter past results.
3. Results are entered one by one or imported from an instrument's CSV file. Each result is compared with its limits
   and flagged as out of specification (OOS) automatically. Limits are inclusive.
4. Once every result is in, a reviewer approves or rejects them. A rejection needs a reason and sends the results
   back for correction. Approval locks the results.
5. An approved sample's Certificate of Analysis lists every test, its specification, the result and Pass or OOS,
   and states whether the batch complies. See the [sample certificate](docs/sample-certificate.pdf).

## Data integrity rules

- **Every change is audited.** Registering a sample, entering, changing, importing, rejecting and approving each
  write an audit event with the user, the time, the old and new value, and the reason.
- **Changing a result needs a reason.** The first entry of a result is recorded as "Initial entry"; any later change
  without a reason is refused.
- **The audit trail is append-only.** A PostgreSQL trigger rejects every `UPDATE`, `DELETE` and `TRUNCATE` on the
  audit table, so not even a bug or a manual SQL session can rewrite history. A test proves it.
- **Reviews are independent.** A user who entered or changed any result on a sample cannot review it, even if they
  hold the reviewer role.
- **Approved results are locked.** No result can be entered or changed after approval.
- **Imports never overwrite.** The CSV import only fills empty results; a value that is already entered must be
  corrected by hand, with a reason.

## Instrument CSV import

The file needs a header with `sample_number`, `test_code` and `value`, in any order. Each row is validated on its
own: valid rows are imported, and every rejected row is reported with its line number and the reason.

```
sample_number,test_code,value
S-000001,ASSAY,99.2
S-000001,HARDNESS,abc
S-000099,ASSAY,100.1
```

```json
{
  "imported": 1,
  "errors": [
    {"line": 3, "message": "'abc' is not a number"},
    {"line": 4, "message": "Unknown sample S-000099"}
  ]
}
```

## API

All endpoints use HTTP Basic authentication. Workflow errors are returned as RFC 9457 problem details.

| Method and path | Role | Purpose |
| --- | --- | --- |
| `POST /api/products` | Reviewer | Create a product with its specification |
| `POST /api/samples` | Analyst | Register a sample for a batch |
| `GET /api/samples/{number}` | Any | Sample with its results and OOS flags |
| `PUT /api/samples/{number}/results/{testCode}` | Analyst | Enter or change a result (`value`, `reason`) |
| `POST /api/results/import` | Analyst | Import instrument results from a CSV file |
| `POST /api/samples/{number}/review` | Reviewer | Approve or reject the results (`decision`, `reason`) |
| `GET /api/samples/{number}/audit` | Any | Audit trail of the sample |
| `GET /api/samples/{number}/certificate` | Any | Certificate of Analysis PDF (approved samples only) |

The demo accounts are `analyst`, `reviewer` and `lead` (analyst and reviewer), each with its name as the password.
They are defined in `application.yml`; replace them with your identity provider before real use.

## Run it

Requires Docker.

```sh
docker compose up --build app
```

Then walk through the workflow with the sample files:

```sh
curl -u reviewer:reviewer -H "Content-Type: application/json" -d @samples/product.json localhost:8080/api/products
curl -u analyst:analyst -H "Content-Type: application/json" \
     -d '{"productCode":"PARA-500","batchNumber":"B-2611"}' localhost:8080/api/samples
curl -u analyst:analyst -F file=@samples/instrument-results.csv localhost:8080/api/results/import
curl -u analyst:analyst -X PUT -H "Content-Type: application/json" \
     -d '{"value":88}' localhost:8080/api/samples/S-000001/results/HARDNESS
curl -u reviewer:reviewer -H "Content-Type: application/json" \
     -d '{"decision":"APPROVE"}' localhost:8080/api/samples/S-000001/review
curl -u reviewer:reviewer localhost:8080/api/samples/S-000001/audit
curl -u analyst:analyst localhost:8080/api/samples/S-000001/certificate -o certificate.pdf
```

## Tests

The tests run the real application against PostgreSQL 17 and cover OOS flagging at the limits, reasons for changes,
the audit trail and its trigger, independent review, rejection, row-level CSV validation, role checks and the
content of the generated certificate:

```sh
docker compose run --rm tests
```

CI runs the same command on every push.

## Repository

| Path | Contents |
| --- | --- |
| `src/main/java/minilims/LabService.java` | Workflow rules: registration, results, OOS, import, review, audit |
| `src/main/java/minilims/CoaReport.java` | Fills and exports the Certificate of Analysis |
| `src/main/resources/reports/coa.jrxml` | JasperReports template, editable in Jaspersoft Studio |
| `src/main/resources/db/migration/` | Flyway schema, including the append-only audit trigger |
| `src/test/java/minilims/LabWorkflowTest.java` | End-to-end tests against PostgreSQL |
| `samples/` | Demo product specification and instrument CSV file |

## Known limits

- One sample per batch and one result per test; replicates and retests would need a result history per test.
- The CSV parser splits on commas and does not handle quoted fields, which instrument exports rarely use.
- Users are in-memory demo accounts; electronic signatures (re-entering a password to approve) are not implemented.
