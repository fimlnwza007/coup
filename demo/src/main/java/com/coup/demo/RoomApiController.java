package com.coup.demo;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomApiController {
	private final CoupRoomService rooms;

	public RoomApiController(CoupRoomService rooms) {
		this.rooms = rooms;
	}

	@PostMapping
	public CoupRoomService.RoomView create(@RequestBody RoomRequest request) {
		return rooms.create(request.name(), request.playerId());
	}

	@PostMapping("/{code}/join")
	public CoupRoomService.RoomView join(@PathVariable String code, @RequestBody RoomRequest request) {
		return rooms.join(code, request.name(), request.playerId());
	}

	@GetMapping("/{code}")
	public CoupRoomService.RoomView get(@PathVariable String code, @RequestParam String playerId) {
		return rooms.get(code, playerId);
	}

	@PostMapping("/{code}/start")
	public CoupRoomService.RoomView start(@PathVariable String code, @RequestBody PlayerRequest request) {
		return rooms.start(code, request.playerId());
	}

	@PostMapping("/{code}/action")
	public CoupRoomService.RoomView act(@PathVariable String code, @RequestBody ActionRequest request) {
		return rooms.act(code, request.playerId(), request.action(), request.targetId(), request.keepIndexes());
	}

	public record RoomRequest(String name, String playerId) {}
	public record PlayerRequest(String playerId) {}
	public record ActionRequest(String playerId, String action, String targetId, List<Integer> keepIndexes) {}
}