package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowServiceImplTest {
    private FollowServiceImpl service;
    private FollowMapper mapper;
    private IUserService users;

    @BeforeEach
    void setUp() {
        service = new FollowServiceImpl();
        mapper = mock(FollowMapper.class);
        users = mock(IUserService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userService", users);
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        when(users.getById(2L)).thenReturn(new User().setId(2L));
    }

    @AfterEach
    void clean() { UserHolder.removeUser(); }

    @Test
    void missingIdentityAndInvalidIdsNeverAccessStorage() {
        UserHolder.removeUser();
        assertFalse(service.follow(2L, true).getSuccess());
        assertFalse(service.isFollow(2L).getSuccess());
        assertFalse(service.commonFollow(2L, 1).getSuccess());
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertFalse(service.follow(id, true).getSuccess());
            assertFalse(service.isFollow(id).getSuccess());
            assertFalse(service.commonFollow(id, 1).getSuccess());
        }
        verifyNoInteractions(mapper, users);
    }

    @Test
    void missingTargetsAndNullIntentAreBusinessErrors() {
        assertFalse(service.follow(99L, true).getSuccess());
        assertFalse(service.follow(99L, false).getSuccess());
        assertFalse(service.isFollow(99L).getSuccess());
        assertFalse(service.commonFollow(99L, 1).getSuccess());
        assertFalse(service.follow(2L, null).getSuccess());
        verifyNoInteractions(mapper);
    }

    @Test
    void selfRulesMatchTheChosenContract() {
        assertFalse(service.follow(1L, true).getSuccess());
        assertFalse(service.follow(1L, false).getSuccess());
        assertEquals(false, service.isFollow(1L).getData());
        assertFalse(service.commonFollow(1L, 1).getSuccess());
        verifyNoInteractions(mapper, users);
    }

    @Test
    void mutationsUseTheIdentityAndReturnTheRequestedBoolean() {
        assertEquals(true, service.follow(2L, true).getData());
        assertEquals(false, service.follow(2L, false).getData());
        assertEquals(false, service.follow(2L, false).getData());
        verify(mapper).insertRelation(1, 2);
        verify(mapper, times(2)).deleteRelation(1, 2);
        verify(mapper, never()).existsRelation(anyLong(), anyLong());
    }

    @Test
    void statusChecksBothIdentityAndTarget() {
        when(mapper.existsRelation(1, 2)).thenReturn(true);
        assertEquals(true, service.isFollow(2L).getData());
        verify(mapper).existsRelation(1, 2);
    }

    @Test
    void onlyAConfirmedRelationUniqueKeyConflictIsIdempotent() {
        DuplicateKeyException duplicate = duplicate("tb_follow.existing_pair_index", 1062);
        doThrow(duplicate).when(mapper).insertRelation(1, 2);
        when(mapper.isRelationUniqueIndex("existing_pair_index")).thenReturn(1);
        assertEquals(true, service.follow(2L, true).getData());
        verify(mapper).isRelationUniqueIndex("existing_pair_index");
    }

    @Test
    void primaryAndUnrelatedUniqueConflictsAreNotSwallowed() {
        for (String key : List.of("tb_follow.PRIMARY", "tb_follow.other_unique_key")) {
            DuplicateKeyException duplicate = duplicate(key, 1062);
            doThrow(duplicate).when(mapper).insertRelation(1, 2);
            assertSame(duplicate, assertThrows(DuplicateKeyException.class, () -> service.follow(2L, true)));
        }
    }

    @Test
    void unknownDatabaseErrorsAreNotGuessedToBeDuplicates() {
        for (DuplicateKeyException error : List.of(new DuplicateKeyException("unknown"), duplicate("pair", 999))) {
            doThrow(error).when(mapper).insertRelation(1, 2);
            assertSame(error, assertThrows(DuplicateKeyException.class, () -> service.follow(2L, true)));
        }
        verify(mapper, never()).isRelationUniqueIndex(anyString());
    }

    @Test
    void commonUsersAreBoundedAndRestoredToSqlOrderAfterOneBulkLookup() {
        when(mapper.selectCommonUserIds(1, 2, 5, 5)).thenReturn(List.of(3L, 4L));
        when(users.listByIds(List.of(3L, 4L))).thenReturn(List.of(
                new User().setId(4L).setNickName("four").setPhone("secret").setPassword("secret"),
                new User().setId(3L).setNickName("three").setIcon("three.png")));
        Result response = service.commonFollow(2L, 2);
        @SuppressWarnings("unchecked") List<UserDTO> result = (List<UserDTO>) response.getData();
        assertEquals(List.of(3L, 4L), result.stream().map(UserDTO::getId).toList());
        assertEquals("three.png", result.get(0).getIcon());
        verify(users).listByIds(List.of(3L, 4L));
        verify(mapper).selectCommonUserIds(1, 2, 5, 5);
        verify(users, never()).getById(3L);
        verify(users, never()).getById(4L);
    }

    @Test
    void emptyCommonResultDoesNotIssueABulkLookup() {
        when(mapper.selectCommonUserIds(1, 2, 0, 5)).thenReturn(List.of());
        assertEquals(List.of(), service.commonFollow(2L, 1).getData());
        verify(users, never()).listByIds(anyCollection());
    }

    @Test
    void invalidPagesAreRejectedAndTheLastPageHasABoundedOffset() {
        for (Integer current : new Integer[]{null, 0, -1, 1001, Integer.MAX_VALUE}) {
            assertFalse(service.commonFollow(2L, current).getSuccess());
        }
        verifyNoInteractions(mapper, users);
        when(mapper.selectCommonUserIds(1, 2, 4995, 5)).thenReturn(List.of());
        assertTrue(service.commonFollow(2L, 1000).getSuccess());
        verify(mapper).selectCommonUserIds(1, 2, 4995, 5);
    }

    private DuplicateKeyException duplicate(String key, int code) {
        return new DuplicateKeyException("insert failed", new SQLException(
                "Duplicate entry '1-2' for key '" + key + "'", "23000", code));
    }
}
