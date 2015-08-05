package org.janelia.render.service.dao;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;

import java.io.File;
import java.net.UnknownHostException;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains the single MongoClient instance that is shared for all service requests.
 *
 * From <a href="http://docs.mongodb.org/ecosystem/drivers/java-concurrency/">
 *     http://docs.mongodb.org/ecosystem/drivers/java-concurrency/</a>:
 *
 *     The Java MongoDB driver is thread safe. If you are using in a web serving environment,
 *     for example, you should create a single MongoClient instance, and you can use it in every request.
 *     The MongoClient object maintains an internal pool of connections to the database
 *     (default maximum pool size of 100).
 *     For every request to the DB (find, insert, etc) the Java thread will obtain a connection
 *     from the pool, execute the operation, and release the connection.
 *
 * @author Eric Trautman
 */
public class SharedMongoClient {

    private static SharedMongoClient sharedMongoClient;

    public static MongoClient getInstance()
            throws UnknownHostException {
        if (sharedMongoClient == null) {
            setSharedMongoClient();
        }
        return sharedMongoClient.client;
    }

    private final MongoClient client;

    public SharedMongoClient(final DbConfig dbConfig)
            throws UnknownHostException {
        final ServerAddress serverAddress = new ServerAddress(dbConfig.getHost(), dbConfig.getPort());
        final MongoCredential credential = MongoCredential.createMongoCRCredential(dbConfig.getUserName(),
                                                                                   dbConfig.getAuthenticationDatabase(),
                                                                                   dbConfig.getPassword());
        final MongoClientOptions options = new MongoClientOptions.Builder()
                .connectionsPerHost(dbConfig.getMaxConnectionsPerHost())
                .build();

        LOG.info("creating client for {} with {}", serverAddress, options);

        client = new MongoClient(serverAddress, Collections.singletonList(credential), options);
    }

    private static synchronized void setSharedMongoClient()
            throws UnknownHostException {
        if (sharedMongoClient == null) {
            final File dbConfigFile = new File("logs/render-db.properties");
            final DbConfig dbConfig = DbConfig.fromFile(dbConfigFile);
            sharedMongoClient = new SharedMongoClient(dbConfig);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DbConfig.class);
}
