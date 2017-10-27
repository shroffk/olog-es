package edu.msu.nscl.olog;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.elasticsearch.action.DocWriteResponse.Result;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A Manager class for handling logbook operations
 * 
 * @author kunalshroff
 *
 */
public class LogbookManager {

    public static final String ologLogbookIndex = "olog_logbooks";
    // instance a json mapper
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Logger log = Logger.getLogger(LogbookManager.class.getName());

    /**
     * List all {@link Logbook}s including both active and inactive ones.
     * 
     * @return {@link List} of all active and inactive {@link Logbook}s
     */
    public static List<Logbook> list() {
        try (TransportClient client = new PreBuiltTransportClient(Settings.EMPTY)
                .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9300))) {
            SearchResponse response = client.search(new SearchRequest(ologLogbookIndex)).get();
            List<Logbook> result = new ArrayList<Logbook>();
            response.getHits().forEach(h -> {
                BytesReference b = h.getSourceRef();
                try {
                    result.add(mapper.readValue(b.streamInput(), Logbook.class));
                } catch (IOException e) {
                    log.log(Level.SEVERE, e.getMessage(), e);
                }
            });
            return result;
        } catch (Exception e) {
            log.log(Level.SEVERE, e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    /**
     * List all the currently active {@link Logbook}
     * 
     * @return List of all the active {@link Logbook}s
     */
    public static List<Logbook> listActive() {
        try (TransportClient client = new PreBuiltTransportClient(Settings.EMPTY)
                .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9300))) {
            SearchResponse response = client.prepareSearch(ologLogbookIndex).setTypes("logbook")
                    .setQuery(QueryBuilders.termQuery("state", State.Active.toString())).get();
            List<Logbook> result = new ArrayList<Logbook>();
            response.getHits().forEach(h -> {
                BytesReference b = h.getSourceRef();
                try {
                    result.add(mapper.readValue(b.streamInput(), Logbook.class));
                } catch (IOException e) {
                    log.log(Level.SEVERE, e.getMessage(), e);
                }
            });
            return result;
        } catch (Exception e) {
            log.log(Level.SEVERE, e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    /**
     * Create a simple logbook
     * 
     * @param logbook
     * @return
     */
    public static Optional<Logbook> createLogbook(Logbook logbook) {
        try (TransportClient client = new PreBuiltTransportClient(Settings.EMPTY)
                .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9300))) {
            IndexResponse response = client.prepareIndex(ologLogbookIndex, "logbook", logbook.getName())
                    .setSource(mapper.writeValueAsBytes(logbook), XContentType.JSON).get();

            if (response.getResult().equals(Result.CREATED)) {
                BytesReference ref = client.prepareGet(ologLogbookIndex, "logbook", response.getId()).get()
                        .getSourceAsBytesRef();
                Logbook createdLogbook = mapper.readValue(ref.streamInput(), Logbook.class);
                return Optional.of(createdLogbook);
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Delete the logbook
     * 
     * @param logbook
     * @return
     */
    public static Optional<Logbook> deleteLogbook(Logbook logbook) {
        try (TransportClient client = new PreBuiltTransportClient(Settings.EMPTY)
                .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9300))) {

            UpdateResponse response = client.prepareUpdate(ologLogbookIndex, "logbook", logbook.getName())
                    .setDoc(jsonBuilder().startObject().field("state", State.Inactive).endObject()).get();

            if (response.getResult().equals(Result.UPDATED)) {
                BytesReference ref = client.prepareGet(ologLogbookIndex, "logbook", response.getId()).get()
                        .getSourceAsBytesRef();
                Logbook deletedLogbook = mapper.readValue(ref.streamInput(), Logbook.class);
                return Optional.of(deletedLogbook);
            }
        } catch (DocumentMissingException e) {
            log.log(Level.SEVERE, logbook.getName() + " Does not exist and thus cannot be deleted");
        } catch (Exception e) {
            log.log(Level.SEVERE, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Search for a single logbook based on the logbook name
     * 
     * @param name
     * @return
     */
    public static Optional<Logbook> findLogbook(String logbookName) {
        try (TransportClient client = new PreBuiltTransportClient(Settings.EMPTY)
                .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9300))) {
            return Optional.of(mapper.readValue(client.prepareGet(ologLogbookIndex, "logbook", logbookName).get()
                    .getSourceAsBytesRef().streamInput(), Logbook.class));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

}