package com.devicehive.dao.riak;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.commands.indexes.BinIndexQuery;
import com.basho.riak.client.api.commands.kv.DeleteValue;
import com.basho.riak.client.api.commands.kv.FetchValue;
import com.basho.riak.client.api.commands.kv.StoreValue;
import com.basho.riak.client.api.commands.mapreduce.BucketMapReduce;
import com.basho.riak.client.api.commands.mapreduce.MapReduce;
import com.basho.riak.client.core.RiakFuture;
import com.basho.riak.client.core.query.Location;
import com.basho.riak.client.core.query.Namespace;
import com.basho.riak.client.core.query.functions.Function;
import com.basho.riak.client.core.util.BinaryValue;
import com.devicehive.dao.DeviceDao;
import com.devicehive.dao.NetworkDao;
import com.devicehive.dao.UserDao;
import com.devicehive.dao.riak.model.RiakUser;
import com.devicehive.exceptions.HivePersistenceLayerException;
import com.devicehive.model.enums.UserRole;
import com.devicehive.model.enums.UserStatus;
import com.devicehive.vo.DeviceVO;
import com.devicehive.vo.NetworkVO;
import com.devicehive.vo.UserVO;
import com.devicehive.vo.UserWithNetworkVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Profile({"riak"})
@Repository
public class UserDaoRiakImpl extends RiakGenericDao implements UserDao {

    private static final Namespace COUNTER_NS = new Namespace("counters", "user_counters");
    private static final Namespace USER_NS = new Namespace("user");

    @Autowired
    private RiakClient client;

    @Autowired
    private UserNetworkDaoRiakImpl userNetworkDao;

    @Autowired
    private NetworkDao networkDao;

    @Autowired
    private NetworkDeviceDaoRiakImpl networkDeviceDao;

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private RiakQuorum quorum;

    private Location userCounters;

    private final Map<String, String> sortMap = new HashMap<>();

    public UserDaoRiakImpl() {
        userCounters = new Location(COUNTER_NS, "user_counter");

        sortMap.put("id", "function(a,b){ return a.id %s b.id; }");
        sortMap.put("login", "function(a,b){ return a.login %s b.login; }");
        sortMap.put("role", "function(a,b){ return a.role %s b.role; }");
        sortMap.put("status", "function(a,b){ return a.status %s b.status; }");
        sortMap.put("lastLogin", "function(a,b){ return a.lastLogin %s b.lastLogin; }");
        sortMap.put("googleLogin", "function(a,b){ return a.googleLogin %s b.googleLogin; }");
        sortMap.put("facebookLogin", "function(a,b){ return a.facebookLogin %s b.facebookLogin; }");
        sortMap.put("githubLogin", "function(a,b){ return a.githubLogin %s b.githubLogin; }");
        sortMap.put("entityVersion", "function(a,b){ return a.entityVersion %s b.entityVersion; }");
    }

    @PostConstruct
    public void init() {
        ((NetworkDaoRiakImpl) networkDao).setUserDao(this);
    }

    @Override
    public Optional<UserVO> findByName(String name) {
        UserVO user = findBySomeIdentityName(name, "login");
        return Optional.ofNullable(user);
    }

    @Override
    public UserVO findByGoogleName(String name) {
        return findBySomeIdentityName(name, "googleLogin");
    }

    @Override
    public UserVO findByFacebookName(String name) {
        return findBySomeIdentityName(name, "facebookLogin");
    }

    @Override
    public UserVO findByGithubName(String name) {
        return findBySomeIdentityName(name, "githubLogin");
    }

    @Override
    public Optional<UserVO> findByIdentityName(String login, String googleLogin, String facebookLogin, String githubLogin) {
        UserVO userToCheck;
        userToCheck = findByGoogleName(googleLogin);
        if (userToCheck != null) {
            if (doesUserAlreadyExist(userToCheck, login)) {
                return Optional.of(userToCheck);
            }
        }

        userToCheck = findByFacebookName(facebookLogin);
        if (userToCheck != null) {
            if (doesUserAlreadyExist(userToCheck, login)) {
                return Optional.of(userToCheck);
            }
        }

        userToCheck = findByGithubName(githubLogin);
        if (userToCheck != null) {
            if (doesUserAlreadyExist(userToCheck, login)) {
                return Optional.of(userToCheck);
            }
        }

        return Optional.empty();
    }

    @Override
    public long hasAccessToNetwork(UserVO user, NetworkVO network) {
        Set<Long> networks = userNetworkDao.findNetworksForUser(user.getId());
        if (networks != null && networks.contains(network.getId())) {
            return 1L;
        } else {
            return 0L;
        }
    }

    @Override
    public long hasAccessToDevice(UserVO user, String deviceGuid) {
        Set<Long> networkIds = userNetworkDao.findNetworksForUser(user.getId());
        for (Long networkId : networkIds) {
            Set<DeviceVO> devices = networkDeviceDao.findDevicesForNetwork(networkId).stream()
                    .map(deviceDao::findByUUID)
                    .collect(Collectors.toSet());
            if (devices != null) {
                long guidCount = devices
                        .stream()
                        .map(DeviceVO::getGuid)
                        .filter(g -> g.equals(deviceGuid))
                        .count();
                if (guidCount > 0) {
                    return guidCount;
                }
            }
        }
        return 0L;
    }

    @Override
    public UserWithNetworkVO getWithNetworksById(long id) {
        UserVO user = find(id);
        if (user == null) {
            return null;
        }

        Set<Long> networkIds = userNetworkDao.findNetworksForUser(id);
        UserWithNetworkVO userWithNetworkVO = UserWithNetworkVO.fromUserVO(user);
        if (networkIds != null) {
            //TODO [rafa] [implement bulk fetch method here]
            Set<NetworkVO> networks = new HashSet<>();
            for (Long networkId : networkIds) {
                NetworkVO network = networkDao.find(networkId);
                networks.add(network);
            }
            userWithNetworkVO.setNetworks(networks);
        }
        return userWithNetworkVO;
    }

    @Override
    public int deleteById(long id) {
        Location location = new Location(USER_NS, String.valueOf(id));
        DeleteValue deleteOp = new DeleteValue.Builder(location).build();
        try {
            client.execute(deleteOp);
            return 1;
        } catch (ExecutionException | InterruptedException e) {
            throw new HivePersistenceLayerException("Cannot delete by id", e);
        }
    }

    @Override
    public UserVO find(Long id) {
        try {
            Location location = new Location(USER_NS, String.valueOf(id));
            FetchValue fetchOp = new FetchValue.Builder(location)
                    .withOption(quorum.getReadQuorumOption(), quorum.getReadQuorum())
                    .build();
            RiakUser riakUser = getOrNull(client.execute(fetchOp), RiakUser.class);
            return RiakUser.convertToVo(riakUser);
        } catch (ExecutionException | InterruptedException e) {
            throw new HivePersistenceLayerException("Cannot find by id", e);
        }
    }

    @Override
    public void persist(UserVO user) {
        merge(user);
    }

    @Override
    public UserVO merge(UserVO user) {
        RiakUser entity = RiakUser.convertToEntity(user);
        try {
            if (entity.getId() == null) {
                entity.setId(getId(userCounters));
            }
            Location location = new Location(USER_NS, String.valueOf(entity.getId()));
            StoreValue storeOp = new StoreValue.Builder(entity)
                    .withLocation(location)
                    .withOption(quorum.getWriteQuorumOption(), quorum.getWriteQuorum())
                    .build();
            client.execute(storeOp);
            user.setId(entity.getId());
            return user;
        } catch (ExecutionException | InterruptedException e) {
            throw new HivePersistenceLayerException("Cannot merge user.", e);
        }
    }

    @Override
    public void unassignNetwork(@NotNull UserVO existingUser, @NotNull long networkId) {
        userNetworkDao.delete(existingUser.getId(), networkId);
    }

    @Override
    public List<UserVO> getList(String login, String loginPattern,
                              Integer role, Integer status,
                              String sortField, Boolean sortOrderAsc,
                              Integer take, Integer skip) {


        List<UserVO> result = new ArrayList<>();
        if (login != null) {
            Optional<UserVO> user = findByName(login);
            if (user.isPresent()) {
                result.add(user.get());
            }
        } else {
            try {
                String sortFunction = sortMap.get(sortField);
                if (sortOrderAsc == null) {
                    sortOrderAsc = true;
                }
                BucketMapReduce.Builder builder = new BucketMapReduce.Builder()
                        .withNamespace(USER_NS)
                        .withMapPhase(Function.newAnonymousJsFunction("function(riakObject, keyData, arg) { " +
                                "                if(riakObject.values[0].metadata['X-Riak-Deleted']){ return []; } " +
                                "                else { return Riak.mapValuesJson(riakObject, keyData, arg); }}"))
                        .withReducePhase(Function.newAnonymousJsFunction("function(values, arg) {" +
                                "return values.filter(function(v) {" +
                                "if (v === [] || v.id === null) { return false; }" +
                                "return true;" +
                                "})" +
                                "}"));

                if (loginPattern != null) {
                    loginPattern = loginPattern.replace("%", "");
                    String functionString = String.format(
                        "function(values, arg) {" +
                            "return values.filter(function(v) {" +
                                "var login = v.login;" +
                                "var match = login.indexOf('%s');" +
                                "return match > -1;" +
                            "})" +
                        "}", loginPattern);
                    Function reduceFunction = Function.newAnonymousJsFunction(functionString);
                    builder.withReducePhase(reduceFunction);
                }

                if (role != null) {
                    String roleString = UserRole.getValueForIndex(role).name();
                    String functionString = String.format(
                            "function(values, arg) {" +
                                "return values.filter(function(v) {" +
                                    "var role = v.role;" +
                                    "return role == '%s';" +
                                "})" +
                            "}", roleString);
                    Function reduceFunction = Function.newAnonymousJsFunction(functionString);
                    builder.withReducePhase(reduceFunction);
                }

                if (status != null) {
                    String statusString = UserStatus.getValueForIndex(status).name();
                    String functionString = String.format(
                            "function(values, arg) {" +
                                "return values.filter(function(v) {" +
                                    "var status = v.status;" +
                                    "return status == '%s';" +
                                "})" +
                            "}", statusString);
                    Function reduceFunction = Function.newAnonymousJsFunction(functionString);
                    builder.withReducePhase(reduceFunction);
                }

                if (sortFunction == null) {
                    sortFunction = sortMap.get("id");
                    builder.withReducePhase(Function.newNamedJsFunction("Riak.reduceSort"),
                            String.format(sortFunction, sortOrderAsc ? "<" : ">"),
                            true);
                } else {
                    builder.withReducePhase(Function.newNamedJsFunction("Riak.reduceSort"),
                            String.format(sortFunction, sortOrderAsc ? ">" : "<"),
                            true);
                }

                BucketMapReduce bmr = builder.build();
                RiakFuture<MapReduce.Response, BinaryValue> future = client.executeAsync(bmr);
                future.await();
                MapReduce.Response response = future.get();
                Collection<RiakUser> users = response.getResultsFromAllPhases(RiakUser.class);
                result.addAll(users.stream().map(RiakUser::convertToVo).collect(Collectors.toList()));

                if (skip != null) {
                    result = result.stream().skip(skip).collect(Collectors.toList());
                }

                if (take != null) {
                    result = result.stream().limit(take).collect(Collectors.toList());
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new HivePersistenceLayerException("Cannot execute search user.", e);
            }
        }
        return result;
    }

    private boolean doesUserAlreadyExist(UserVO user, String login) {
        return (!user.getLogin().equals(login) && user.getStatus() != UserStatus.DELETED);
    }

    private UserVO findBySomeIdentityName(String name, String identityName) {
        if (name == null) {
            return null;
        }
        BinIndexQuery biq = new BinIndexQuery.Builder(USER_NS, identityName, name).build();
        try {
            BinIndexQuery.Response response = client.execute(biq);
            List<BinIndexQuery.Response.Entry> entries = response.getEntries();
            if (entries.isEmpty()) {
                return null;
            }
            Location location = entries.get(0).getRiakObjectLocation();
            FetchValue fetchOp = new FetchValue.Builder(location)
                    .withOption(quorum.getReadQuorumOption(), quorum.getReadQuorum())
                    .build();
            RiakUser riakUser = getOrNull(client.execute(fetchOp), RiakUser.class);
            return RiakUser.convertToVo(riakUser);
        } catch (ExecutionException | InterruptedException e) {
            throw new HivePersistenceLayerException("Cannot find by identity.", e);
        }
    }

}
