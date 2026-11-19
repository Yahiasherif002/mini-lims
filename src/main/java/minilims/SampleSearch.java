package minilims;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.util.List;
import minilims.LabService.SampleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

@Component
class SampleSearch {

    record SampleDocument(String number, String productCode, String productName, String batchNumber, String status,
                          List<String> outOfSpec) {
    }

    private static final Logger log = LoggerFactory.getLogger(SampleSearch.class);
    private static final String INDEX = "samples";
    private static final List<String> FIELDS =
            List.of("number", "productCode", "productName", "batchNumber", "status", "outOfSpec");

    private final ElasticsearchClient client;

    SampleSearch(ElasticsearchClient client) {
        this.client = client;
    }

    @TransactionalEventListener
    void index(SampleView sample) {
        var document = new SampleDocument(sample.number(), sample.productCode(), sample.productName(),
                sample.batchNumber(), sample.status().name(),
                sample.results().stream().filter(r -> Boolean.TRUE.equals(r.outOfSpec()))
                        .map(LabService.ResultView::testCode).toList());
        try {
            client.index(i -> i.index(INDEX).id(document.number()).document(document).refresh(Refresh.WaitFor));
        } catch (Exception e) {
            log.warn("Could not index sample {}; search results may be stale", document.number(), e);
        }
    }

    List<SampleDocument> find(String text) {
        try {
            return client.search(s -> s.index(INDEX).ignoreUnavailable(true).allowNoIndices(true).size(50)
                                    .query(q -> q.simpleQueryString(m -> m.query(text).fields(FIELDS)
                                            .defaultOperator(Operator.And))),
                            SampleDocument.class)
                    .hits().hits().stream().map(Hit::source).toList();
        } catch (Exception e) {
            log.warn("Search failed for '{}'", text, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Search is unavailable");
        }
    }
}
