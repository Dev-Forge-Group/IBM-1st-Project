package org.myweb.uniplace.domain.property.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.myweb.uniplace.domain.property.api.dto.request.RoomSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceSearchRequest;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.CommonSpace;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.springframework.data.jpa.domain.Specification;

/** Resource scope is part of both the page query and its count query. */
public final class PropertySpecifications {
    private PropertySpecifications() {}

    public static Specification<Building> buildings(Collection<Integer> ids, String existElv,
            Integer minParking, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(scope(root.get("buildingId"), ids, cb));
            predicates.add(cb.equal(root.get("deleteYn"), "N"));
            equal(predicates, root.get("existElv"), existElv, cb);
            minimum(predicates, root.get("parkingCapacity"), minParking, false, cb);
            if (keyword != null && !keyword.isEmpty()) {
                String pattern = pattern(keyword);
                predicates.add(cb.or(cb.like(cb.lower(root.get("buildingNm")), pattern),
                        cb.like(cb.lower(root.get("buildingAddr")), pattern)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<Room> rooms(Collection<Integer> ids, RoomSearchRequest request) {
        return (root, query, cb) -> {
            RoomSearchRequest r = request == null ? new RoomSearchRequest() : request;
            List<Predicate> p = new ArrayList<>();
            p.add(scope(root.get("roomId"), ids, cb));
            p.add(cb.equal(root.get("deleteYn"), "N"));
            p.add(cb.equal(root.get("building").get("deleteYn"), "N"));
            equal(p, root.get("building").get("buildingId"), r.getBuildingId(), cb);
            like(p, root.get("building").get("buildingNm"), r.getBuildingNm(), cb);
            like(p, root.get("building").get("buildingAddr"), r.getBuildingAddr(), cb);
            minimum(p, root.get("building").get("parkingCapacity"), r.getMinParkingCapacity(), false, cb);
            equal(p, root.get("roomNo"), r.getRoomNo(), cb);
            equal(p, root.get("floor"), r.getFloor(), cb);
            minimum(p, root.get("roomSize"), r.getMinRoomSize(), false, cb);
            maximum(p, root.get("roomSize"), r.getMaxRoomSize(), false, cb);
            equal(p, root.get("roomType"), r.getRoomType(), cb);
            equal(p, root.get("petAllowedYn"), r.getPetAllowedYn(), cb);
            minimum(p, root.get("deposit"), r.getMinDeposit(), true, cb);
            maximum(p, root.get("deposit"), r.getMaxDeposit(), true, cb);
            minimum(p, root.get("rentPrice"), r.getMinRentPrice(), false, cb);
            maximum(p, root.get("rentPrice"), r.getMaxRentPrice(), false, cb);
            minimum(p, root.get("manageFee"), r.getMinManageFee(), true, cb);
            maximum(p, root.get("manageFee"), r.getMaxManageFee(), true, cb);
            equal(p, root.get("rentType"), r.getRentType(), cb);
            equal(p, root.get("roomSt"), r.getRoomSt(), cb);
            equal(p, root.get("sunDirection"), r.getSunDirection(), cb);
            minimum(p, root.get("roomCapacity"), r.getMinRoomCapacity(), false, cb);
            maximum(p, root.get("roomCapacity"), r.getMaxRoomCapacity(), false, cb);
            minimum(p, root.get("rentMin"), r.getMinRentMin(), false, cb);
            maximum(p, root.get("rentMin"), r.getMaxRentMin(), false, cb);
            like(p, root.get("roomOptions"), r.getRoomOptions(), cb);
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    public static Specification<CommonSpace> spaces(Collection<Integer> buildingIds, SpaceSearchRequest request) {
        return (root, query, cb) -> {
            SpaceSearchRequest r = request == null ? new SpaceSearchRequest() : request;
            List<Predicate> p = new ArrayList<>();
            p.add(scope(root.get("building").get("buildingId"), buildingIds, cb));
            p.add(cb.equal(root.get("building").get("deleteYn"), "N"));
            equal(p, root.get("building").get("buildingId"), r.getBuildingId(), cb);
            like(p, root.get("building").get("buildingNm"), r.getBuildingNm(), cb);
            like(p, root.get("building").get("buildingAddr"), r.getBuildingAddr(), cb);
            minimum(p, root.get("building").get("parkingCapacity"), r.getMinParkingCapacity(), false, cb);
            like(p, root.get("spaceNm"), r.getSpaceNm(), cb);
            equal(p, root.get("spaceFloor"), r.getSpaceFloor(), cb);
            minimum(p, root.get("spaceCapacity"), r.getMinSpaceCapacity(), false, cb);
            maximum(p, root.get("spaceCapacity"), r.getMaxSpaceCapacity(), false, cb);
            like(p, root.get("spaceOptions"), r.getSpaceOptions(), cb);
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private static Predicate scope(Path<Integer> path, Collection<Integer> ids, CriteriaBuilder cb) {
        return ids == null || ids.isEmpty() ? cb.disjunction() : path.in(ids);
    }

    private static void equal(List<Predicate> p, Path<?> path, Object value, CriteriaBuilder cb) {
        if (value != null) p.add(cb.equal(path, value));
    }

    private static void like(List<Predicate> p, Path<String> path, String value, CriteriaBuilder cb) {
        if (value != null && !value.isEmpty()) p.add(cb.like(cb.lower(path), pattern(value)));
    }

    private static String pattern(String value) { return "%" + value.toLowerCase(Locale.ROOT) + "%"; }

    private static <T extends Comparable<? super T>> void minimum(List<Predicate> p, Path<T> path, T value,
            boolean allowNull, CriteriaBuilder cb) {
        if (value != null) {
            Predicate bound = cb.greaterThanOrEqualTo(path, value);
            p.add(allowNull ? cb.or(cb.isNull(path), bound) : bound);
        }
    }

    private static <T extends Comparable<? super T>> void maximum(List<Predicate> p, Path<T> path, T value,
            boolean allowNull, CriteriaBuilder cb) {
        if (value != null) {
            Predicate bound = cb.lessThanOrEqualTo(path, value);
            p.add(allowNull ? cb.or(cb.isNull(path), bound) : bound);
        }
    }
}
