package com.coup.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CoupRoomService {
	private static final List<String> ROLES = List.of("Duke", "Assassin", "Captain", "Ambassador", "Contessa");
	private final Map<String, Room> rooms = new ConcurrentHashMap<>();

	public RoomView create(String name, String playerId) {
		Room room = new Room();
		room.code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
		room.hostId = playerId;
		room.players.add(new Player(playerId, cleanName(name)));
		room.log.add("ห้องพร้อมแล้ว รอเพื่อนเข้าร่วม");
		rooms.put(room.code, room);
		return view(room, playerId);
	}

	public RoomView join(String code, String name, String playerId) {
		Room room = findRoom(code);
		synchronized (room) {
			if (room.started) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "เกมเริ่มไปแล้ว");
			}
			if (room.players.stream().noneMatch(player -> player.id.equals(playerId))) {
				if (room.players.size() >= 6) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "ห้องเต็มแล้ว");
				}
				room.players.add(new Player(playerId, cleanName(name)));
				room.log.add(cleanName(name) + " เข้าร่วมห้อง");
			}
			return view(room, playerId);
		}
	}

	public RoomView get(String code, String playerId) {
		Room room = findRoom(code);
		synchronized (room) {
			return view(room, playerId);
		}
	}

	public RoomView start(String code, String playerId) {
		Room room = findRoom(code);
		synchronized (room) {
			Player host = player(room, playerId);
			if (!room.hostId.equals(playerId)) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "เฉพาะเจ้าของห้องเท่านั้นที่เริ่มเกมได้");
			}
			if (room.players.size() < 2) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ต้องมีผู้เล่นอย่างน้อย 2 คน");
			}
			List<String> deck = new ArrayList<>();
			for (String role : ROLES) {
				for (int copy = 0; copy < 3; copy++) {
					deck.add(role);
				}
			}
			Collections.shuffle(deck);
			for (Player player : room.players) {
				player.cards.clear();
				player.cards.add(new Card(deck.remove(deck.size() - 1)));
				player.cards.add(new Card(deck.remove(deck.size() - 1)));
				player.coins = 2;
			}
			room.deck = deck;
			room.started = true;
			room.turnIndex = 0;
			room.log.add(host.name + " เริ่มเกม Coup");
			return view(room, playerId);
		}
	}

	public RoomView act(String code, String playerId, String action, String targetId, List<Integer> keepIndexes) {
		Room room = findRoom(code);
		synchronized (room) {
			if (!room.started || room.finished) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "เกมยังไม่เริ่มหรือจบไปแล้ว");
			}
			if (room.pendingAction != null) {
				respond(room, playerId, action);
				return view(room, playerId);
			}
			Player actor = player(room, playerId);
			if (room.players.get(room.turnIndex) != actor) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "ยังไม่ถึงเทิร์นของคุณ");
			}
			String move = action == null ? "" : action.toUpperCase(Locale.ROOT);
			if (move.equals("EXCHANGE") && playerId.equals(room.pendingExchangePlayerId)) {
				boolean complete = exchange(actor, room, keepIndexes);
				if (complete) {
					advance(room);
				}
				return view(room, playerId);
			}
			if (actor.coins >= 10 && !move.equals("COUP")) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "มีเหรียญตั้งแต่ 10 เหรียญ ต้องทำรัฐประหาร");
			}
			Player target = null;
			if (List.of("STEAL", "ASSASSINATE", "COUP").contains(move)) {
				target = player(room, targetId);
				if (target == actor || !target.alive()) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "เลือกเป้าหมายที่ยังอยู่ในเกม");
				}
			}
			switch (move) {
				case "INCOME" -> {
					actor.coins += 1;
					room.log.add(actor.name + " รับรายได้ +1 เหรียญ");
					advance(room);
				}
				case "AID", "TAX", "STEAL", "ASSASSINATE", "EXCHANGE" -> {
					if (move.equals("ASSASSINATE")) {
						requireCoins(actor, 3);
						actor.coins -= 3;
					}
					String claimedRole = switch (move) {
						case "TAX", "AID" -> "Duke";
						case "STEAL" -> "Captain";
						case "ASSASSINATE" -> "Assassin";
						default -> "Ambassador";
					};
					room.pendingAction = new PendingAction(move, actor.id, target == null ? null : target.id, claimedRole);
					room.log.add(actor.name + " อ้างว่าเป็น " + claimedRole + " เพื่อ " + actionName(move));
					setResponders(room, room.pendingAction, actor.id);
					resolveWhenUnanswered(room);
				}
				case "COUP" -> {
					requireCoins(actor, 7);
					actor.coins -= 7;
					Player coupTarget = Objects.requireNonNull(target);
					loseInfluence(coupTarget, room);
					room.log.add(actor.name + " ทำรัฐประหารใส่ " + coupTarget.name);
					advance(room);
				}
				default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ไม่รู้จักคำสั่งนี้");
			}
			return view(room, playerId);
		}
	}

	private void respond(Room room, String playerId, String response) {
		PendingAction pending = room.pendingAction;
		if (!playerId.equals(pendingResponder(pending))) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "ยังไม่ถึงตาคุณตอบโต้");
		}
		String choice = response == null ? "" : response.toUpperCase(Locale.ROOT);
		Player responder = player(room, playerId);
		if (choice.equals("ALLOW")) {
			pending.responderIndex++;
			resolveWhenUnanswered(room);
			return;
		}
		if (choice.equals("BLOCK")) {
			String blockRole = blockRole(pending, responder);
			if (pending.blocked || blockRole == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "คุณไม่มีสิทธิ์ขัดขวางคำสั่งนี้");
			}
			pending.blocked = true;
			pending.claimantRole = blockRole;
			room.log.add(responder.name + " ขัดขวางโดยอ้างว่าเป็น " + blockRole);
			setResponders(room, pending, responder.id);
			resolveWhenUnanswered(room);
			return;
		}
		if (!choice.equals("CHALLENGE")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "เลือกยอมให้ผ่าน ท้าทาย หรือขัดขวาง");
		}
		Player claimant = player(room, pending.claimantId);
		String claimedRole = pending.claimantRole;
		Card claimedCard = claimant.cards.stream().filter(card -> card.alive
				&& List.of(claimedRole.split("/")).contains(card.role)).findFirst().orElse(null);
		boolean claimWasTrue = claimedCard != null;
		if (claimWasTrue) {
			Card revealedCard = Objects.requireNonNull(claimedCard);
			loseInfluence(responder, room);
			claimant.cards.remove(revealedCard);
			room.deck.add(revealedCard.role);
			Collections.shuffle(room.deck);
			claimant.cards.add(new Card(room.deck.remove(room.deck.size() - 1)));
			room.log.add(responder.name + " ท้าทายไม่สำเร็จ; " + claimant.name + " เป็น " + claimedRole + " จริง");
		} else {
			loseInfluence(claimant, room);
			room.log.add(claimant.name + " ถูกจับได้ว่าไม่ได้เป็น " + claimedRole);
		}
		if (pending.blocked) {
			if (claimWasTrue) {
				room.log.add("การขัดขวางสำเร็จ");
				room.pendingAction = null;
				advance(room);
			} else {
				room.log.add("การขัดขวางถูกท้าทายสำเร็จ");
				resolvePendingAction(room);
			}
		} else if (claimWasTrue) {
			resolvePendingAction(room);
		} else {
			room.pendingAction = null;
			advance(room);
		}
	}

	private void setResponders(Room room, PendingAction pending, String claimantId) {
		pending.claimantId = claimantId;
		pending.responderIds = new ArrayList<>();
		int claimantIndex = room.players.indexOf(player(room, claimantId));
		for (int step = 1; step < room.players.size(); step++) {
			Player candidate = room.players.get((claimantIndex + step) % room.players.size());
			if (candidate.alive()) {
				pending.responderIds.add(candidate.id);
			}
		}
		pending.responderIndex = 0;
	}

	private String pendingResponder(PendingAction pending) {
		return pending.responderIndex < pending.responderIds.size()
				? pending.responderIds.get(pending.responderIndex) : null;
	}

	private void resolveWhenUnanswered(Room room) {
		PendingAction pending = room.pendingAction;
		while (pending != null && pending.responderIndex < pending.responderIds.size()) {
			Player responder = player(room, pending.responderIds.get(pending.responderIndex));
			if (responder.alive()) {
				return;
			}
			pending.responderIndex++;
		}
		if (pending != null) {
			if (pending.blocked) {
				room.log.add("คำสั่งถูกขัดขวาง");
				room.pendingAction = null;
				advance(room);
			} else {
				resolvePendingAction(room);
			}
		}
	}

	private void resolvePendingAction(Room room) {
		PendingAction pending = room.pendingAction;
		Player actor = player(room, pending.actorId);
		Player target = pending.targetId == null ? null : player(room, pending.targetId);
		switch (pending.type) {
			case "AID" -> {
				actor.coins += 2;
				room.log.add(actor.name + " รับความช่วยเหลือจากต่างประเทศ +2 เหรียญ");
			}
			case "TAX" -> {
				actor.coins += 3;
				room.log.add(actor.name + " เก็บภาษีในฐานะ Duke +3 เหรียญ");
			}
			case "STEAL" -> {
				Player stealTarget = Objects.requireNonNull(target);
				int amount = Math.min(2, stealTarget.coins);
				stealTarget.coins -= amount;
				actor.coins += amount;
				room.log.add(actor.name + " ขโมย " + amount + " เหรียญจาก " + stealTarget.name);
			}
			case "ASSASSINATE" -> {
				Player assassinationTarget = Objects.requireNonNull(target);
				loseInfluence(assassinationTarget, room);
				room.log.add(actor.name + " ลอบสังหาร " + assassinationTarget.name);
			}
			case "EXCHANGE" -> beginExchange(actor, room);
			default -> throw new IllegalStateException("Unexpected pending action: " + pending.type);
		}
		room.pendingAction = null;
		if (!pending.type.equals("EXCHANGE")) {
			advance(room);
		}
	}

	private String blockRole(PendingAction pending, Player player) {
		return switch (pending.type) {
			case "AID" -> "Duke";
			case "STEAL" -> player.id.equals(pending.targetId) ? "Captain/Ambassador" : null;
			case "ASSASSINATE" -> player.id.equals(pending.targetId) ? "Contessa" : null;
			default -> null;
		};
	}

	private String actionName(String action) {
		return switch (action) {
			case "AID" -> "ขอความช่วยเหลือ";
			case "TAX" -> "เก็บภาษี";
			case "STEAL" -> "ขโมย";
			case "ASSASSINATE" -> "ลอบสังหาร";
			default -> "แลกอิทธิพล";
		};
	}

	private boolean exchange(Player actor, Room room, List<Integer> keepIndexes) {
		if (room.pendingExchangePlayerId == null) {
			List<String> options = new ArrayList<>();
			actor.cards.stream().filter(card -> card.alive).map(card -> card.role).forEach(options::add);
			options.add(room.deck.remove(room.deck.size() - 1));
			options.add(room.deck.remove(room.deck.size() - 1));
			room.pendingExchangePlayerId = actor.id;
			room.pendingExchangeRoles = options;
			return false;
		}
		if (!room.pendingExchangePlayerId.equals(actor.id)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "มีผู้เล่นอื่นกำลังเลือกไพ่แลกเปลี่ยน");
		}
		int activeCount = (int) actor.cards.stream().filter(card -> card.alive).count();
		List<String> options = room.pendingExchangeRoles;
		if (keepIndexes == null || keepIndexes.size() != activeCount
				|| keepIndexes.stream().distinct().count() != activeCount
				|| keepIndexes.stream().anyMatch(index -> index == null || index < 0 || index >= options.size())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "เลือกตัวละครที่ต้องการเก็บให้ครบ " + activeCount + " ใบ");
		}
		List<String> kept = keepIndexes.stream().map(options::get).toList();
		List<Card> lost = actor.cards.stream().filter(card -> !card.alive).toList();
		actor.cards.clear();
		lost.forEach(card -> actor.cards.add(new Card(card.role, false)));
		kept.forEach(role -> actor.cards.add(new Card(role)));
		for (int index = 0; index < options.size(); index++) {
			if (!keepIndexes.contains(index)) {
				room.deck.add(options.get(index));
			}
		}
		Collections.shuffle(room.deck);
		room.pendingExchangePlayerId = null;
		room.pendingExchangeRoles = null;
		room.log.add(actor.name + " แลกเปลี่ยนอิทธิพล");
		return true;
	}

	private void beginExchange(Player actor, Room room) {
		List<String> options = new ArrayList<>();
		actor.cards.stream().filter(card -> card.alive).map(card -> card.role).forEach(options::add);
		options.add(room.deck.remove(room.deck.size() - 1));
		options.add(room.deck.remove(room.deck.size() - 1));
		room.pendingExchangePlayerId = actor.id;
		room.pendingExchangeRoles = options;
	}

	private void requireCoins(Player player, int amount) {
		if (player.coins < amount) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "เหรียญไม่พอสำหรับคำสั่งนี้");
		}
	}

	private void loseInfluence(Player player, Room room) {
		List<Card> active = player.cards.stream().filter(card -> card.alive).toList();
		if (!active.isEmpty()) {
			active.get((int) (Math.random() * active.size())).alive = false;
			if (!player.alive()) {
				room.log.add(player.name + " หมดอิทธิพลและออกจากเกม");
			}
		}
	}

	private void advance(Room room) {
		List<Player> alive = room.players.stream().filter(Player::alive).toList();
		if (alive.size() <= 1) {
			room.finished = true;
			if (!alive.isEmpty()) {
				room.log.add(alive.get(0).name + " ชนะเกม Coup!");
			}
			return;
		}
		for (int step = 1; step <= room.players.size(); step++) {
			int next = (room.turnIndex + step) % room.players.size();
			if (room.players.get(next).alive()) {
				room.turnIndex = next;
				return;
			}
		}
	}

	private RoomView view(Room room, String playerId) {
		Player self = room.players.stream().filter(player -> player.id.equals(playerId)).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "คุณไม่ได้อยู่ในห้องนี้"));
		List<PlayerView> players = room.players.stream()
				.map(player -> new PlayerView(player.id, player.name, player.coins,
						(int) player.cards.stream().filter(card -> card.alive).count(), player.alive(),
						player.id.equals(playerId) ? player.cards.stream().map(card -> new CardView(card.role, card.alive)).toList() : List.of()))
				.toList();
		String currentPlayerId = room.pendingAction != null ? pendingResponder(room.pendingAction)
				: room.started && !room.finished ? room.players.get(room.turnIndex).id : null;
		PendingView pendingView = null;
		if (room.pendingAction != null) {
			PendingAction pending = room.pendingAction;
			Player claimant = player(room, pending.claimantId);
			Player actor = player(room, pending.actorId);
			Player target = pending.targetId == null ? null : player(room, pending.targetId);
			Player responder = pendingResponder(pending) == null ? null : player(room, pendingResponder(pending));
			String availableBlock = responder == null || pending.blocked ? null : blockRole(pending, responder);
			pendingView = new PendingView(pending.type, actor.name, target == null ? null : target.name, claimant.name,
					pending.claimantRole, pendingResponder(pending), availableBlock != null,
					availableBlock == null ? null : availableBlock);
		}
		return new RoomView(room.code, room.hostId, room.started, room.finished, currentPlayerId,
				room.deck == null ? 15 : room.deck.size(), players, List.copyOf(room.log), self.id,
			playerId.equals(room.pendingExchangePlayerId) ? List.copyOf(room.pendingExchangeRoles) : List.of(), pendingView);
	}

	private Room findRoom(String code) {
		Room room = code == null ? null : rooms.get(code.trim().toUpperCase(Locale.ROOT));
		if (room == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบห้องนี้ ตรวจรหัสแล้วลองอีกครั้ง");
		}
		return room;
	}

	private Player player(Room room, String id) {
		return room.players.stream().filter(player -> player.id.equals(id)).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "ไม่พบผู้เล่นในห้องนี้"));
	}

	private String cleanName(String name) {
		if (name == null || name.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ใส่ชื่อก่อนเข้าห้อง");
		}
		String cleaned = name.trim();
		if (cleaned.length() > 20) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ชื่อต้องไม่เกิน 20 ตัวอักษร");
		}
		return cleaned;
	}

	private static final class Room {
		private String code;
		private String hostId;
		private final List<Player> players = new ArrayList<>();
		private final List<String> log = new ArrayList<>();
		private List<String> deck;
		private String pendingExchangePlayerId;
		private List<String> pendingExchangeRoles;
		private PendingAction pendingAction;
		private int turnIndex;
		private boolean started;
		private boolean finished;
	}

	private static final class Player {
		private final String id;
		private final String name;
		private final List<Card> cards = new ArrayList<>();
		private int coins;

		private Player(String id, String name) {
			this.id = id;
			this.name = name;
		}

		private boolean alive() {
			return cards.stream().anyMatch(card -> card.alive);
		}
	}

	private static final class Card {
		private final String role;
		private boolean alive = true;

		private Card(String role) {
			this.role = role;
		}

		private Card(String role, boolean alive) {
			this.role = role;
			this.alive = alive;
		}
	}

	private static final class PendingAction {
		private final String type;
		private final String actorId;
		private final String targetId;
		private String claimantId;
		private String claimantRole;
		private boolean blocked;
		private List<String> responderIds = new ArrayList<>();
		private int responderIndex;

		private PendingAction(String type, String actorId, String targetId, String claimantRole) {
			this.type = type;
			this.actorId = actorId;
			this.targetId = targetId;
			this.claimantId = actorId;
			this.claimantRole = claimantRole;
		}
	}

	public record CardView(String role, boolean alive) {}
	public record PlayerView(String id, String name, int coins, int influences, boolean alive, List<CardView> cards) {}
	public record PendingView(String action, String actorName, String targetName, String claimantName, String claimedRole,
			String reactionPlayerId, boolean canBlock, String blockRole) {}
	public record RoomView(String code, String hostId, boolean started, boolean finished, String currentPlayerId,
			int cardsRemaining, List<PlayerView> players, List<String> log, String selfId, List<String> exchangeOptions,
			PendingView pendingAction) {}
}