## db4j - database for java

db4j is a transactional database engine
- pure java queries with in-memory semantics
- journaled, MVCC, persistent
- green threads for effectively unlimited concurrency
- primitive arrays, POJOs and indexes

## a quick example

a simple persistent async RESTful "mailbox" app

```
public class Chat extends Database {
    HunkCount count;
    Btrees.IO<User> users;
    Btrees.SI namemap;
    Btrees.IS messages;
    public static class User implements Serializable {
        public String name, bio;
        public User(String name,String bio) { this.name=name; this.bio=bio; }
        String format() { return "user: " + name + " <" + bio + ">"; }
    }    
    public String route(String query) throws Pausable {
        String cmds[]=query.split("/"), cmd=cmds.length > 1 ? cmds[1]:"none";
        Integer id = parse(cmds,2);
        return offer(tid -> { switch (cmd) {
            case "dir" : return users.getall(tid).vals().stream().map(User::format).collect(Collectors.joining("\n"));
            case "get" : return users.context().set(tid).set(id,null).get(users).val.format();
            case "list": return messages.findPrefix(tid,id).vals().stream().collect(Collectors.joining("\n"));
            case "msg" : return "sent: " + messages.insert(tid,id,cmds[3]).val;
            case "user": return "" + namemap.find(tid,cmds[2]);
            case "new" : 
                    User user = new User(cmds[2],cmds[3]);
                    int krow = count.plus(tid,1);
                    users.insert(tid,krow,user);
                    namemap.insert(tid,user.name,krow);
                    return "" + krow;
            default: return "";
        }});
    public static void main(String[] args) throws Exception {
        if (Kilim.trampoline(false,args)) return;
        Chat chat = new Chat();
        chat.start("chat.db",args.length > 0);
        new kilim.http.HttpServer(8080, req -> chat.route(req.uriPath)+"\n");
    }
}

```

this implements a simple persistent RESTful "mailbox" app
- uses the webserver built-in to kilim, but jetty or another server can be used instead
- data is stored persistently in the database file `chat.db`
- `localhost:8080/new/john doe/software professional with an interest in java and linux`
- this demo is included in the repository in `demos/diary-kws`
- fully async server and queries (`Kilim.trampoline`)


## installation

```
        <dependency>
            <groupId>org.db4j</groupId>
            <artifactId>db4j</artifactId>
            <version>0.9.9</version>
        </dependency>
```

## fundamentals

persistent data structures, ie `Hunkable`
- `HunkArray` stores primitive data types in a space and cpu efficient form
- `BTree` stores a mapping between two types, and can be used as an index or to store variable sized elements, eg POJOs and strings
- `HunkCount` is a simple scalar value, eg to store the number of elements in an array


high level structures:
- `Table` has `Hunkable` fields
- `Database` has `Table` and `Hunkable` fields
- fields are automatically initialized

`kilim` is used to automatically convert imperative java code into green threads aka fibers, 
allowing an effectively unlimited number of simultaneous queries.
this is done with bytecode instrumentation, either ahead of time or at runtime.
a quasar-based port exists but isn't currently distributed (see issues)

on linux, disk is accessed using `posix_fadvise` to cut down on unneeded caching and pipeline reads. 
on other platforms, performance will be greatly reduced and db4j is untested - buyer beware.
there's a port to java's `AsyncFileChannel`, which is broken on linux but at least on windows is performant.
post an issue if you're interested in testing this



## contact / mailing list

* the [db4j mailing list](https://groups.google.com/forum/#!forum/db4j)
* https://github.com/db4j/db4j

