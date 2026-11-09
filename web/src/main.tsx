import {useEffect,useRef,useState,type FormEvent} from 'react';
import {createRoot} from 'react-dom/client';
import './style.css';

type Scanner={id:string;modality:string;label:string};
type Appointment={id:string;scannerId:string;demoRef:string;startsAt:string;endsAt:string;status:'BOOKED'|'CANCELLED';version:number};
type History={action:string;version:number;at:string};
class ApiError extends Error {constructor(public status:number,msg:string){super(msg);}}
async function api<T>(path:string, method='GET', data?:unknown):Promise<T>{
  const response=await fetch('/api/'+path,{method,headers:data===undefined?undefined:{'Content-Type':'application/json'},
    body:data===undefined?undefined:JSON.stringify(data)});
  const body=await response.json();
  if(!response.ok) throw new ApiError(response.status,body.error??'Request failed');
  return body;
}
const datePart=(s:string)=>new Date(s).toISOString().slice(0,10);
const clock=(s:string)=>new Date(s).toISOString().slice(11,16);
const today=()=>new Date().toISOString().slice(0,10);
const randomRef=()=> 'DEMO-'+crypto.randomUUID().slice(0,6).toUpperCase();

function App(){
  const [day,setDay]=useState(today);
  const [scanners,setScanners]=useState<Scanner[]>([]);
  const [list,setList]=useState<Appointment[]>([]);
  const [selected,setSelected]=useState<Appointment>();
  const [history,setHistory]=useState<History[]>([]);
  const [room,setRoom]=useState('');
  const [start,setStart]=useState('09:00');
  const [duration,setDuration]=useState(30);
  const [ref,setRef]=useState(randomRef);
  const [draftDay,setDraftDay]=useState(today);
  const [filter,setFilter]=useState('all');
  const [busy,setBusy]=useState(false);
  const [pending,setPending]=useState(true);
  const [refresh,setRefresh]=useState(0);
  const [message,setMessage]=useState('');
  const [error,setError]=useState('');
  const request=useRef<{signature:string;key:string} | undefined>(undefined);
  const messageOf=(e:unknown)=>e instanceof Error?e.message:'Request failed';
  useEffect(()=>{
    let active=true;
    api<Scanner[]>('scanners').then(data=>{if(active){setScanners(data);setRoom(r=>r||data[0]?.id||'');}}).catch(e=>{if(active)setError(messageOf(e));});
    return ()=>{active=false;};
  },[]);
  useEffect(()=>{
    let active=true;
    setPending(true);
    api<Appointment[]>('appointments?date='+encodeURIComponent(day))
      .then(data=>{if(active)setList(data);}).catch(e=>{if(active){setList([]);setError(messageOf(e));}})
      .finally(()=>{if(active)setPending(false);});
    return ()=>{active=false;};
  },[day,refresh]);
  useEffect(()=>{
    let active=true;
    setHistory([]);
    if(selected)api<History[]>('appointments/'+selected.id+'/events')
      .then(data=>{if(active)setHistory(data);}).catch(e=>{if(active)setError(messageOf(e));});
    return ()=>{active=false;};
  },[selected]);
  const edit=(a:Appointment)=>{
    setSelected(a);setRoom(a.scannerId);setStart(clock(a.startsAt));setDraftDay(datePart(a.startsAt));
    setDuration(Math.round((Date.parse(a.endsAt)-Date.parse(a.startsAt))/60000));setRef(a.demoRef);
  };
  const reset=(d=day)=>{setSelected(undefined);setRef(randomRef());setDraftDay(d);};
  const act=async(fn:()=>Promise<Appointment>)=>{
    setBusy(true);setError('');setMessage('');
    try{
      const next=await fn();setDay(datePart(next.startsAt));edit(next);
      request.current=undefined;
      setMessage(next.status==='CANCELLED'?'Appointment cancelled.':next.version?'Appointment moved.':'Appointment booked.');
      setRefresh(n=>n+1);
    }catch(e){
      setError(messageOf(e));
      if(e instanceof ApiError&&e.status===409){setRefresh(n=>n+1);if(selected)setSelected(undefined);}
    }finally{setBusy(false);}
  };
  const save=(e:FormEvent)=>{
    e.preventDefault();
    const slot={scannerId:room,startsAt:draftDay+'T'+start+':00Z',durationMinutes:duration};
    if(selected) void act(()=>api<Appointment>('appointments/'+selected.id,'PATCH',{version:selected.version,slot}));
    else {
      const signature=JSON.stringify({demoRef:ref,slot});
      if(request.current?.signature!==signature)request.current={signature,key:crypto.randomUUID()};
      const requestKey=request.current.key;
      void act(()=>api<Appointment>('appointments','POST',{requestKey,demoRef:ref,slot}));
    }
  };
  const rows=filter==='all'?list:list.filter(a=>a.scannerId===filter);
  const labels=Object.fromEntries(scanners.map(r=>[r.id,r.label]));
  return <>
    <header><span>OPERATIONS WORK SAMPLE</span><h1>Imaging Schedule Desk</h1><p>Scanner schedules and appointment changes</p></header>
    <main>
      <p className="banner">Demo workspace · fictional references only · all times UTC</p>
      {error&&<p className="alert" role="alert">{error}</p>}{message&&<p className="notice" role="status">{message}</p>}
      <div className="grid">
        <section>
          <div className="bar"><h2>Daily worklist</h2><button className="secondary" disabled={busy||pending} onClick={()=>setRefresh(n=>n+1)}>Refresh</button></div>
          <div className="filters">
            <label>Worklist date<input aria-label="Worklist date" type="date" required value={day} onChange={e=>{if(e.target.value){setDay(e.target.value);reset(e.target.value);}}}/></label>
            <label>Scanner filter<select value={filter} onChange={e=>setFilter(e.target.value)}><option value="all">All scanners</option>{scanners.map(s=><option key={s.id} value={s.id}>{s.label}</option>)}</select></label>
          </div>
          <div className="counts"><strong>{rows.filter(a=>a.status==='BOOKED').length}</strong> booked · {rows.filter(a=>a.status==='CANCELLED').length} cancelled</div>
          {pending?<p>Loading schedule…</p>:rows.length?<div className="table"><table>
            <thead><tr><th>Reference</th><th>Scanner</th><th>Time (UTC)</th><th>Status</th></tr></thead>
            <tbody>{rows.map(a=><tr key={a.id}><td><button className="link" disabled={busy} onClick={()=>edit(a)}>{a.demoRef}</button></td><td>{labels[a.scannerId]??a.scannerId}</td><td>{clock(a.startsAt)}–{clock(a.endsAt)}{datePart(a.startsAt)!==datePart(a.endsAt)?' (+1 day)':''}</td><td><span className={'pill '+a.status.toLowerCase()}>{a.status.toLowerCase()}</span></td></tr>)}</tbody>
          </table></div>:<p className="empty">No appointments on this view.</p>}
        </section>
        <section>
          <div className="bar"><h2>{selected?'Appointment details':'Book a slot'}</h2>
          {selected&&<button className="secondary" disabled={busy} onClick={()=>reset()}>New booking</button>}</div>
          <form onSubmit={save}>
            <fieldset disabled={busy||selected?.status==='CANCELLED'}>
              <label>Demo reference<input required pattern="DEMO-[A-Z0-9]{1,12}" readOnly={Boolean(selected)} value={ref} onChange={e=>setRef(e.target.value.toUpperCase())}/></label>
              <label>Scanner<select required value={room} onChange={e=>setRoom(e.target.value)}>{scanners.map(s=><option key={s.id} value={s.id}>{s.label}</option>)}</select></label>
              <div className="pair"><label>Slot date<input type="date" required value={draftDay} onChange={e=>setDraftDay(e.target.value)}/></label><label>Start (UTC)<input type="time" step="900" required value={start} onChange={e=>setStart(e.target.value)}/></label></div>
              <label>Duration<select value={duration} onChange={e=>setDuration(Number(e.target.value))}>{[15,30,45,60,75,90,105,120].map(n=><option key={n} value={n}>{n} minutes</option>)}</select></label>
              <div className="actions"><button type="submit" disabled={!room}>{selected?'Save new slot':'Book appointment'}</button>
              {selected&&<button type="button" className="secondary" onClick={()=>void act(()=>api<Appointment>('appointments/'+selected.id+'/cancel','POST',{version:selected.version}))}>Cancel booking</button>}</div>
            </fieldset>
          </form>
          {selected&&<div className="history"><h3>Change history</h3><ul>{history.map(h=><li key={h.version}>{h.action.toLowerCase()} · {clock(h.at)} UTC · v{h.version}</li>)}</ul></div>}
        </section>
      </div>
    </main>
  </>;
}
createRoot(document.getElementById('root')!).render(<App/>);
