package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.client.ClientFavouriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client/favourites")
@RequiredArgsConstructor
public class ClientFavouriteController extends AppController {

    private final ClientFavouriteService favouriteService;

    @GetMapping
    public ResponseEntity<Response> getFavourites(@RequestHeader("Authorization") String authHeader) throws VeloriaException {
        return success(ResponseCode.OK, "Favourites fetched", favouriteService.getFavourites(token(authHeader)));
    }

    @GetMapping("/uuids")
    public ResponseEntity<Response> getFavouriteUuids(@RequestHeader("Authorization") String authHeader) throws VeloriaException {
        return success(ResponseCode.OK, "Favourite UUIDs fetched", favouriteService.getFavouriteUuids(token(authHeader)));
    }

    @PostMapping("/add")
    public ResponseEntity<Response> addFavourite(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> body) throws VeloriaException {
        UUID productUuid = UUID.fromString(body.get("productUuid"));
        favouriteService.addFavourite(token(authHeader), productUuid);
        return success(ResponseCode.OK, "Added to favourites", null);
    }

    @DeleteMapping("/{productUuid}")
    public ResponseEntity<Response> removeFavourite(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID productUuid) throws VeloriaException {
        favouriteService.removeFavourite(token(authHeader), productUuid);
        return success(ResponseCode.OK, "Removed from favourites", null);
    }

    private String token(String authHeader) {
        return authHeader.replace("Bearer ", "").trim();
    }
}
