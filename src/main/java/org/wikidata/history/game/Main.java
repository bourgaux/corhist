package org.wikidata.history.game;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Context;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public static void main(String[] args) throws SQLException {
    ViolationDatabase violationDatabase = new ViolationDatabase();
    Runtime.getRuntime().addShutdownHook(new Thread(violationDatabase::close));

    CorrectionLookup correctionLookup = new CorrectionLookup("*.ser");
    ViolationDatabaseUpdater violationDatabaseUpdater = new ViolationDatabaseUpdater(violationDatabase, correctionLookup);

    new Thread(() -> {
      LOGGER.info("initializing database");
      violationDatabaseUpdater.loadFromWikidataQuery();
      LOGGER.info("database initialization done");
    }).start();

    violationDatabaseUpdater.startToLoadFromRecentChanges();
    Runtime.getRuntime().addShutdownHook(new Thread(violationDatabaseUpdater::close));

    Game game = new ConstraintCorrectionsGame(violationDatabase);
    Javalin.create()
            .enableCorsForOrigin("*")
            .get("/corhist/dgame", ctx -> {
              String action = ctx.queryParam("action");
              if ("desc".equals(action)) {
                jsonp(ctx, game.getDescription());
              } else if ("tiles".equals(action)) {
                int num = Math.min(30, Integer.parseInt(ctx.queryParam("num", "10")));
                List<Game.Tile> tiles = game.generateTiles(num, ctx.queryParam("lang", "en"));
                Map<String, Object> result = new HashMap<>();
                result.put("tiles", tiles);
                if (tiles.size() < num) {
                  result.put("low", 1);
                }
                jsonp(ctx, result);
              } else if ("log_action".equals(action)) {
                game.log(ctx.queryParam("user"), Integer.parseInt(ctx.queryParam("tile")), ctx.queryParam("decision"));
              }
            })
            .get("/corhist/stats", ctx -> ctx.json(violationDatabase.countByState()))
            .start(Integer.parseInt(System.getenv().getOrDefault("PORT", "7000")));
  }

  private static <T> void jsonp(Context ctx, T result) throws JsonProcessingException {
    String callback = ctx.queryParam("callback");
    if (callback == null) {
      ctx.json(result);
    } else {
      ctx.result(callback + "(" + OBJECT_MAPPER.writeValueAsString(result) + ")").contentType("application/javascript");
    }
  }
}
